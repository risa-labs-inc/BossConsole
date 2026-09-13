-- Rotate the Vault master_encryption_key, re-encrypting everything under it.
--
-- Run 2026-09-09. Kept because a rotation is not a one-off: this is the
-- procedure, and it is parameterless apart from the column list at the top.
--
-- Properties that make it safe to run against production:
--   * ONE DO block, so it is atomic no matter how the caller wraps statements.
--   * It fingerprints every row's plaintext (HMAC-SHA256 with an ephemeral per-run key) under the old key BEFORE
--     touching anything, and after the swap re-derives those fingerprints
--     through public.decrypt_text - which re-reads the Vault, so the check
--     proves both that the swap took effect and that every plaintext survived.
--     Any mismatch, or any row count drift, RAISES and therefore rolls back.
--     A broken rotation cannot commit.
--   * No key and no plaintext is ever returned to the caller.
--   * The old key is preserved under an explicit name. Do NOT delete it while
--     any pre-rotation database backup is still retained - those backups are
--     encrypted under it and are unrecoverable without it.
--
-- Before running, re-derive the column list. Two independent methods should
-- agree, as they did here (8 columns, 184 rows):
--   a) functions whose body calls encrypt_text/decrypt_text, and on what;
--   b) every text column in schema public named ~ '(_enc$|_encrypted$|...)'.
-- A column present in neither is the one risk atomicity does not cover.

-- Rotate master_encryption_key. ONE DO block, so it is atomic regardless of how
-- the caller wraps statements: any verification failure raises, and nothing
-- commits. No key and no plaintext is ever returned to the caller.
--
-- Why rotation and not just the revoke: public.get_encryption_key() was
-- SECURITY DEFINER and executable by `anon`, and the project anon key is
-- compiled into the public BossConsole repo. A live unauthenticated call
-- returned the key. Revoking access does not un-disclose it.
do $$
declare
  old_key    text;
  new_key    text := encode(extensions.gen_random_bytes(32), 'base64');
  fingerprint_key bytea := extensions.gen_random_bytes(32);
  secret_id  uuid;
  backup_name text;
  table_name text;
  -- table, column, pk, storage-envelope prefix, application read path.
  --
  -- The envelope column exists because #417 stores TOTP secrets as
  -- 'v1:' || encrypt_text(...) rather than bare base64. Same cipher, same key -
  -- only the framing differs - so it rotates like everything else once the
  -- prefix is stripped and re-applied.
  --
  -- The read path matters: verification in step 5 must go through the function
  -- the APPLICATION uses, not a convenient equivalent. recovery codes and TOTP
  -- are read through safe_decrypt_* wrappers that tolerate formats plain
  -- decrypt_text does not, so verifying them with decrypt_text would prove
  -- something nobody relies on.
  cols text[][] := array[
    array['secrets','password_encrypted','id','','public.decrypt_text'],
    array['secret_metadata','recovery_codes_encrypted','id','','public.safe_decrypt_recovery_codes'],
    array['secret_metadata','twofa_secret','id','v1:','public.safe_decrypt_twofa_secret'],
    array['qbo_token_state','client_id_enc','id','','public.decrypt_text'],
    array['qbo_token_state','client_secret_enc','id','','public.decrypt_text'],
    array['qbo_token_state','refresh_token_enc','id','','public.decrypt_text'],
    array['qbo_token_state','access_token_enc','id','','public.decrypt_text'],
    array['google_token_state','private_key_enc','id','','public.decrypt_text'],
    array['google_token_state','access_token_enc','id','','public.decrypt_text']
  ];
  -- BEFORE INSERT/UPDATE triggers on mapped tables that would rewrite what we
  -- store. Each must be disabled for the re-encryption and restored after.
  known_triggers text[][] := array[
    array['secret_metadata','encrypt_twofa_secret_trigger']
  ];
  i int; n int; expected int; verified int; mismatched int;
  adapter text; trg record;
begin
  -- Serialize invocations, then block readers and writers on mapped tables before
  -- reading the key. Supabase's postgres role cannot SELECT FOR UPDATE on
  -- vault.secrets: writes are exposed only through vault.update_secret.
  -- Coordinate any other key-management operation outside this procedure.
  perform pg_catalog.pg_advisory_xact_lock(423, 1200);
  -- Core vault tables are required. Brokers are optional deployments; absent
  -- tables contain nothing to rotate. Existing broker tables keep the exact
  -- documented column contract and an unexpected schema still fails closed.
  if to_regclass('public.secrets') is null or to_regclass('public.secret_metadata') is null then
    raise exception 'Core secret tables are missing';
  end if;
  select array_agg(array[cols[j][1], cols[j][2], cols[j][3], cols[j][4], cols[j][5]] order by j)
    into cols
    from generate_subscripts(cols, 1) j
    where to_regclass(format('public.%I', cols[j][1])) is not null
      and exists (
        select 1 from pg_catalog.pg_attribute a
        where a.attrelid = to_regclass(format('public.%I', cols[j][1]))
          and a.attname = cols[j][2] and a.attnum > 0 and not a.attisdropped);
  for table_name in
    select distinct cols[j][1] from generate_subscripts(cols, 1) j order by 1
  loop
    execute format('lock table public.%I in access exclusive mode', table_name);
  end loop;
  select id into strict secret_id from vault.secrets
    where name = 'master_encryption_key';
  old_key := public.get_encryption_key();

  -- #417's TOTP envelope is handled by the map above, but ONLY version v1.
  -- A row carrying any other framing is a format this script has not been
  -- taught, so refuse rather than corrupt it. This replaces the earlier blanket
  -- "TOTP is installed, refuse everything" guard: the contract that an
  -- unrecognised format stops the rotation is preserved, it is just no longer
  -- triggered by a format we now support.
  if to_regclass('public.secret_metadata') is not null
     and exists (
       select 1 from pg_catalog.pg_attribute
       where attrelid = 'public.secret_metadata'::regclass
         and attname = 'twofa_secret' and attnum > 0 and not attisdropped)
     and exists (
       select 1 from public.secret_metadata
       where twofa_secret is not null and twofa_secret not like 'v1:%') then
    raise exception
      'secret_metadata.twofa_secret holds an unrecognised storage envelope; extend rotation coverage before running';
  end if;

  -- A safe_decrypt_* wrapper is how this codebase signals "this field has a
  -- bespoke storage format". Every one of them must be named as the read path
  -- of some mapped column, or the next such field lands silently unrotated.
  -- That is precisely how #417 would have slipped past the name-pattern sweep
  -- below: `twofa_secret` matches neither _enc nor _encrypted.
  for adapter in
    select p.proname
      from pg_catalog.pg_proc p
      join pg_catalog.pg_namespace ns on ns.oid = p.pronamespace
     where ns.nspname = 'public' and p.proname like 'safe\_decrypt\_%'
  loop
    if not exists (
      select 1 from generate_subscripts(cols, 1) j
      where cols[j][5] = 'public.' || adapter
    ) then
      raise exception 'No rotation adapter for public.%; extend rotation coverage before running', adapter;
    end if;
  end loop;

  -- A BEFORE INSERT/UPDATE trigger on a mapped table can rewrite what step 2
  -- stores. #417's is expected and is disabled around the re-encryption below;
  -- any other one is unreviewed, so fail closed rather than silently let it
  -- reinterpret ciphertext.
  if exists (
    select 1
      from pg_catalog.pg_trigger tg
      join pg_catalog.pg_class rel on rel.oid = tg.tgrelid
      join pg_catalog.pg_namespace ns on ns.oid = rel.relnamespace
     where ns.nspname = 'public'
       and not tg.tgisinternal
       and tg.tgenabled <> 'D'
       and (tg.tgtype & 2) <> 0                 -- BEFORE
       and (tg.tgtype & (4 | 16)) <> 0          -- INSERT or UPDATE
       and exists (select 1 from generate_subscripts(cols, 1) j where cols[j][1] = rel.relname)
       and not exists (
         select 1 from generate_subscripts(known_triggers, 1) k
         where known_triggers[k][1] = rel.relname and known_triggers[k][2] = tg.tgname)
  ) then
    raise exception 'Unreviewed BEFORE trigger on a mapped table; it may rewrite rotated ciphertext';
  end if;
  if exists (
    select 1 from pg_catalog.pg_attribute a
    join pg_catalog.pg_class rel on rel.oid = a.attrelid
    join pg_catalog.pg_namespace ns on ns.oid = rel.relnamespace
    where ns.nspname = 'public' and a.attname ~ '(_enc$|_encrypted$)'
      and a.attnum > 0 and not a.attisdropped
      and rel.relkind in ('r', 'p')
      and not exists (
        select 1 from generate_subscripts(cols, 1) j
        where cols[j][1] = rel.relname and cols[j][2] = a.attname)
  ) then
    raise exception 'Unmapped encrypted column; extend rotation coverage before running';
  end if;

  -- The old key may be the documented hex string or a deployed base64 key.
  -- Decrypt with its original bytes, then encrypt with the new key's bytes;
  -- equal textual lengths are not a cryptographic requirement.

  -- 0. Every mapped row must be readable through its own read path BEFORE we
  -- touch anything. The safe_decrypt_* wrappers return NULL rather than raising,
  -- so without this an already-corrupt row would only surface at step 5 as
  -- "a row was missed" - fail-closed, but pointing at the wrong thing.
  for i in 1 .. array_length(cols, 1) loop
    execute format($f$
      select count(*) from public.%1$I
       where %2$I is not null and %3$s(%2$I) is null
    $f$, cols[i][1], cols[i][2], cols[i][5]) into n;
    if n > 0 then
      raise exception
        'ROLLING BACK: % rows of %.% cannot be read through % before rotation; fix or re-key those rows first',
        n, cols[i][1], cols[i][2], cols[i][5];
    end if;
  end loop;

  -- 1. Fingerprint plaintext with a per-run HMAC key kept only in this block.
  -- A spilled temp page must not provide unsalted password hashes.
  create temp table rot_fp(tbl text, col text, pk text, fp text) on commit drop;
  expected := 0;
  for i in 1 .. array_length(cols, 1) loop
    execute format($f$
      insert into rot_fp
      select %1$L, %2$L, %3$I::text,
             pg_catalog.encode(extensions.hmac(
               pg_catalog.convert_to(%4$s(%2$I)::text, 'utf8'), $1, 'sha256'), 'hex')
      from public.%1$I where %2$I is not null
    $f$, cols[i][1], cols[i][2], cols[i][3], cols[i][5])
      using fingerprint_key;
    get diagnostics n = row_count;
    expected := expected + n;
  end loop;
  raise notice 'fingerprinted % rows', expected;

  -- 2. Re-encrypt in place, old key to new key. Never materialises plaintext.
  --
  -- #417's trigger REJECTS a value that already carries the v1: envelope
  -- ('TOTP input must be plaintext, not a storage envelope'), which is correct
  -- for application writes and fatal for a re-encryption. Disable the reviewed
  -- triggers for the duration; the ALTER is transactional, so a failure
  -- anywhere below restores them with everything else.
  for i in 1 .. array_length(known_triggers, 1) loop
    if to_regclass(format('public.%I', known_triggers[i][1])) is not null
       and exists (
         select 1 from pg_catalog.pg_trigger tg
         where tg.tgrelid = to_regclass(format('public.%I', known_triggers[i][1]))
           and tg.tgname = known_triggers[i][2]) then
      execute format('alter table public.%I disable trigger %I',
                     known_triggers[i][1], known_triggers[i][2]);
    end if;
  end loop;

  for i in 1 .. array_length(cols, 1) loop
    execute format($f$
      update public.%1$I
         set %2$I = %4$L || pg_catalog.encode(
               extensions.encrypt(
                 extensions.decrypt(pg_catalog.decode(pg_catalog.substr(%2$I, %5$s),'base64'), $1::bytea, 'aes'),
                 $2::bytea, 'aes'), 'base64')
       where %2$I is not null
    $f$, cols[i][1], cols[i][2], cols[i][3], cols[i][4], (length(cols[i][4]) + 1)::text)
      using old_key, new_key;
  end loop;

  for i in 1 .. array_length(known_triggers, 1) loop
    if to_regclass(format('public.%I', known_triggers[i][1])) is not null
       and exists (
         select 1 from pg_catalog.pg_trigger tg
         where tg.tgrelid = to_regclass(format('public.%I', known_triggers[i][1]))
           and tg.tgname = known_triggers[i][2]) then
      execute format('alter table public.%I enable trigger %I',
                     known_triggers[i][1], known_triggers[i][2]);
    end if;
  end loop;

  -- 3. Keep EVERY outgoing key, including on subsequent rotations. Retain it
  -- while any backup encrypted under it exists. Never delete on verification.
  backup_name := 'master_encryption_key_retired_' || pg_catalog.gen_random_uuid()::text;
  perform vault.create_secret(old_key, backup_name,
    'Retired master key. Retain while any backup encrypted under this key exists.');

  -- 4. Swap the live key.
  perform vault.update_secret(secret_id, new_key, 'master_encryption_key',
    'Master key for encrypting user secrets. Rotated ' || pg_catalog.clock_timestamp()::text || '.');

  -- 5. Verify through each column's APPLICATION read path, which re-reads the
  --    vault - so this proves the swap took effect, that every plaintext
  --    survived, and that the path the app actually uses still returns it.
  --    A safe_decrypt_* wrapper returns NULL on failure rather than raising;
  --    NULL cannot match a fingerprint, so that still fails closed.
  verified := 0; mismatched := 0;
  for i in 1 .. array_length(cols, 1) loop
    execute format($f$
      select
        count(*) filter (where f.fp = pg_catalog.encode(extensions.hmac(pg_catalog.convert_to(%4$s(t.%2$I)::text, 'utf8'), $1, 'sha256'), 'hex')),
        count(*) filter (where f.fp is distinct from pg_catalog.encode(extensions.hmac(pg_catalog.convert_to(%4$s(t.%2$I)::text, 'utf8'), $1, 'sha256'), 'hex'))
      from public.%1$I t
      join rot_fp f on f.tbl = %1$L and f.col = %2$L and f.pk = t.%3$I::text
      where t.%2$I is not null
    $f$, cols[i][1], cols[i][2], cols[i][3], cols[i][5]) into n, mismatched using fingerprint_key;
    verified := verified + n;
    if mismatched > 0 then
      raise exception 'ROLLING BACK: % rows of %.% no longer decrypt to their original plaintext',
        mismatched, cols[i][1], cols[i][2];
    end if;
  end loop;

  if verified <> expected then
    raise exception 'ROLLING BACK: verified % rows but fingerprinted % - a row was missed', verified, expected;
  end if;

  -- Remove fingerprints now, including when the operator invokes us again
  -- inside the same outer transaction. Errors roll back their creation.
  drop table pg_temp.rot_fp;
  raise notice 'ROTATED. % of % rows verified byte-identical under the new key.', verified, expected;
end $$;
