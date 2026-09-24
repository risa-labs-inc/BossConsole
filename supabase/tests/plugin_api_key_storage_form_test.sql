-- pgTAP tests for the at-rest storage form of plugin-store API key material
-- (20260923172000_plugin_api_key_storage_form).
-- Run with: supabase test db
--
-- The edge function has always hashed keys before INSERT and stored only a
-- display mask, but nothing in the schema enforced that form: key_hash and
-- key_prefix were bare TEXT, and users hold direct INSERT/UPDATE RLS grants
-- on their own rows. The migration adds CHECK constraints (64-hex digest in
-- key_hash, 16-char mask in key_prefix) and an idempotent in-place repair
-- for legacy rows.
--
-- These call the REAL repair function and the REAL validate RPC, not copies
-- of their bodies, and exercise the constraints through the same direct
-- INSERT/UPDATE paths a PostgREST client holds.
--
-- The auth.users shim is live-DB drift insurance only: on a fresh CI
-- database the column exists and this is a no-op.

begin;
select plan(22);

-- Fixture: live-DB drift shim (no-op on a fresh database).

insert into auth.users (id, email, email_confirmed_at) values
    ('d0000000-0000-4000-8000-000000000001', 'storageform@keys.test', now()),
    ('d0000000-0000-4000-8000-000000000002', 'legacystore@keys.test', now());

-- Fixed material for this suite:
--   raw conform key  boss_pk_a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6
--     digest 4349a95d0392fccd07d0b88fd040ec4aa14d78df6830129ac31e0c4a465a6ab1
--   raw legacy key  boss_pk_ABCDEFGHIJKLMNOPQRSTUVWXYZabcdef
--     digest 6c8d96fc6e16730e4d68f6339efed2b66496022635d49b44fd80fb1057c89c46
--   raw prefix-leak key boss_pk_z9y8x7w6v5u4t3s2r1q0p9o8n7m6l5k4
--     digest 3bda2d00590e5fd4123979990ec5479ed677d9b82c122323a94e5f05fc1e7dd1

-- ===========================================================================
-- A. The enforced shape exists
-- ===========================================================================

select ok(
    exists (
        select 1
          from pg_catalog.pg_constraint
         where conrelid = 'public.plugin_api_keys'::regclass
           and contype = 'c'
           and conname = 'plugin_api_keys_key_hash_digest_check'
    ),
    'key_hash is pinned to the 64-hex digest form by a named CHECK constraint'
);

select ok(
    exists (
        select 1
          from pg_catalog.pg_constraint
         where conrelid = 'public.plugin_api_keys'::regclass
           and contype = 'c'
           and conname = 'plugin_api_keys_key_prefix_masked_check'
    ),
    'key_prefix is pinned to the 16-char mask form by a named CHECK constraint'
);

-- ===========================================================================
-- B. Real-shaped material still works end to end
-- ===========================================================================

select lives_ok(
    $q$insert into public.plugin_api_keys
        (id, user_id, name, key_prefix, key_hash, scopes)
        values
        ('d0000000-0000-4000-8000-00000000000a',
         'd0000000-0000-4000-8000-000000000001',
         'conform',
         'boss_pk_c1234567',
         '4349a95d0392fccd07d0b88fd040ec4aa14d78df6830129ac31e0c4a465a6ab1',
         array['publish'])$q$,
    'a key row in the canonical form is still insertable'
);

select is(
    (select key_id::text from public.validate_plugin_api_key(
        '4349a95d0392fccd07d0b88fd040ec4aa14d78df6830129ac31e0c4a465a6ab1') limit 1),
    'd0000000-0000-4000-8000-00000000000a',
    'a presented key validates through its sha256 digest'
);

-- ===========================================================================
-- C. Raw material is refused, on the same direct-write paths PostgREST grants
-- ===========================================================================

select throws_ok(
    $q$insert into public.plugin_api_keys
        (id, user_id, name, key_prefix, key_hash, scopes)
        values
        ('d0000000-0000-4000-8000-0000000000f1',
         'd0000000-0000-4000-8000-000000000001',
         'rawhash',
         'boss_pk_r1234567',
         'boss_pk_a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6',
         array['publish'])$q$,
    '23514',
    'new row for relation "plugin_api_keys" violates check constraint "plugin_api_keys_key_hash_digest_check"',
    'raw key material in key_hash is refused'
);

select throws_ok(
    $q$insert into public.plugin_api_keys
        (id, user_id, name, key_prefix, key_hash, scopes)
        values
        ('d0000000-0000-4000-8000-0000000000f2',
         'd0000000-0000-4000-8000-000000000001',
         'rawprefix',
         'boss_pk_a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6',
         '4349a95d0392fccd07d0b88fd040ec4aa14d78df6830129ac31e0c4a465a6ab1',
         array['publish'])$q$,
    '23514',
    'new row for relation "plugin_api_keys" violates check constraint "plugin_api_keys_key_prefix_masked_check"',
    'full key material in key_prefix is refused'
);

select throws_ok(
    $q$insert into public.plugin_api_keys
        (id, user_id, name, key_prefix, key_hash, scopes)
        values
        ('d0000000-0000-4000-8000-0000000000f3',
         'd0000000-0000-4000-8000-000000000001',
         'longprefix',
         'boss_pk_c12345678',
         '4349a95d0392fccd07d0b88fd040ec4aa14d78df6830129ac31e0c4a465a6ab1',
         array['publish'])$q$,
    '23514',
    'new row for relation "plugin_api_keys" violates check constraint "plugin_api_keys_key_prefix_masked_check"',
    'a 17-char prefix (mask plus one char of material) is refused'
);

select throws_ok(
    $q$insert into public.plugin_api_keys
        (id, user_id, name, key_prefix, key_hash, scopes)
        values
        ('d0000000-0000-4000-8000-0000000000f4',
         'd0000000-0000-4000-8000-000000000001',
         'upperhash',
         'boss_pk_u1234567',
         '4349A95D0392FCCD07D0B88FD040EC4AA14D78DF6830129AC31E0C4A465A6AB1',
         array['publish'])$q$,
    '23514',
    'new row for relation "plugin_api_keys" violates check constraint "plugin_api_keys_key_hash_digest_check"',
    'an uppercase-hex digest is refused: hashApiKey output is lowercase only'
);

select throws_ok(
    $q$update public.plugin_api_keys
        set key_hash = 'boss_pk_ABCDEFGHIJKLMNOPQRSTUVWXYZabcdef'
     where id = 'd0000000-0000-4000-8000-00000000000a'$q$,
    '23514',
    'new row for relation "plugin_api_keys" violates check constraint "plugin_api_keys_key_hash_digest_check"',
    'updating a live row to raw key material is refused'
);

select throws_ok(
    $q$update public.plugin_api_keys
        set key_prefix = 'boss_pk_z9y8x7w6v5u4t3s2r1q0p9o8n7m6l5k4'
     where id = 'd0000000-0000-4000-8000-00000000000a'$q$,
    '23514',
    'new row for relation "plugin_api_keys" violates check constraint "plugin_api_keys_key_prefix_masked_check"',
    'updating a live row to full key material in key_prefix is refused'
);

-- ===========================================================================
-- D. Legacy repair: rows that predate the constraints are brought back into
--    form - dead material is revoked and scrubbed, live conforming keys
--    keep working
-- ===========================================================================
-- Drop both constraints so the suite can seed rows the way a legacy writer
-- would have written them. They are re-added below; the whole suite runs in
-- a transaction that rolls back.

alter table public.plugin_api_keys
    drop constraint plugin_api_keys_key_hash_digest_check;
alter table public.plugin_api_keys
    drop constraint plugin_api_keys_key_prefix_masked_check;

-- 0b: a legacy row whose key_hash is the RAW key (nothing was hashed).
-- 0c: a legacy row with garbage in key_hash that was never a credential.
-- 0d: a twin: the raw form of a key that is ALSO stored properly (row 0a).
-- 0e: a legacy row with a valid digest but the FULL key parked in key_prefix.
insert into public.plugin_api_keys (id, user_id, name, key_prefix, key_hash, scopes) values
    ('d0000000-0000-4000-8000-00000000000b',
     'd0000000-0000-4000-8000-000000000002',
     'legacy-raw', 'bpk_legacy',
     'boss_pk_ABCDEFGHIJKLMNOPQRSTUVWXYZabcdef', array['publish']),
    ('d0000000-0000-4000-8000-00000000000c',
     'd0000000-0000-4000-8000-000000000002',
     'legacy-garbage', 'boss_pk_g1234567',
     'legacy-plaintext-hash', array['publish']),
    ('d0000000-0000-4000-8000-00000000000d',
     'd0000000-0000-4000-8000-000000000002',
     'legacy-twin', 'boss_pk_t1234567',
     'boss_pk_a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6', array['publish']),
    ('d0000000-0000-4000-8000-00000000000e',
     'd0000000-0000-4000-8000-000000000002',
     'legacy-prefixleak', 'boss_pk_z9y8x7w6v5u4t3s2r1q0p9o8n7m6l5k4',
     '3bda2d00590e5fd4123979990ec5479ed677d9b82c122323a94e5f05fc1e7dd1', array['publish']);

create temporary table t_repair as
    select * from public.repair_plugin_api_key_material();

-- Counts are floor checks, not exact ones: this suite runs against a shared
-- database that may hold other rows, and every row the repair touches proves
-- the same invariant. Idempotency below is what pins the exact behaviour.
select cmp_ok((select revoked from t_repair), '>=', 3::bigint,
    'the raw-key row, the garbage row and the twin were revoked, none activated or salvaged');
select cmp_ok((select scrubbed from t_repair), '>=', 2::bigint,
    'the two unmasked prefixes were scrubbed');

select is(
    (select key_hash from public.plugin_api_keys
     where id = 'd0000000-0000-4000-8000-00000000000b'),
    (select pg_catalog.encode(extensions.digest('d0000000-0000-4000-8000-00000000000b', 'sha256'), 'hex')),
    'a raw key resting in key_hash is scrubbed to the digest of the row id, never hashed into a live credential'
);

select ok(
    (select revoked_at is not null
       and key_hash <> '6c8d96fc6e16730e4d68f6339efed2b66496022635d49b44fd80fb1057c89c46'
      from public.plugin_api_keys
     where id = 'd0000000-0000-4000-8000-00000000000b'),
    'a key whose plaintext rested readable in key_hash is treated as exposed and revoked, not revived'
);

select is(
    (select count(*) from public.validate_plugin_api_key(
        '6c8d96fc6e16730e4d68f6339efed2b66496022635d49b44fd80fb1057c89c46')),
    0::bigint,
    'a raw-key row does not validate after the repair: presenting the original key matches nothing'
);

select ok(
    (select revoked_at is not null
       and key_hash ~ '^[0-9a-f]{64}$'
       and key_hash <> 'legacy-plaintext-hash'
      from public.plugin_api_keys
     where id = 'd0000000-0000-4000-8000-00000000000c'),
    'the garbage-hash row is revoked and its material scrubbed to a digest'
);

select is(
    (select key_id::text from public.validate_plugin_api_key(
        '4349a95d0392fccd07d0b88fd040ec4aa14d78df6830129ac31e0c4a465a6ab1') limit 1),
    'd0000000-0000-4000-8000-00000000000a',
    'the twin is dead but the properly-stored copy still resolves: one credential, one live row'
);

select is(
    (select key_prefix from public.plugin_api_keys
     where id = 'd0000000-0000-4000-8000-00000000000e'),
    'boss_pk_scrubbed',
    'the full key parked in key_prefix is replaced by the fixed placeholder'
);

select is(
    (select key_id::text from public.validate_plugin_api_key(
        '3bda2d00590e5fd4123979990ec5479ed677d9b82c122323a94e5f05fc1e7dd1') limit 1),
    'd0000000-0000-4000-8000-00000000000e',
    'scrubbing the prefix does not break the key: it still validates via its digest'
);

-- Re-run: everything is already in form, so nothing may change.
create temporary table t_repair2 as
    select * from public.repair_plugin_api_key_material();

select ok(
    (select revoked = 0 and scrubbed = 0 from t_repair2),
    'the repair is idempotent: a second run repairs nothing'
);

-- Restore the constraints exactly the way the migration does, which also
-- proves the DROP-IF-EXISTS + ADD shape is re-runnable.
alter table public.plugin_api_keys
    drop constraint if exists plugin_api_keys_key_hash_digest_check;
alter table public.plugin_api_keys
    add constraint plugin_api_keys_key_hash_digest_check
    check (key_hash ~ '^[0-9a-f]{64}$');
alter table public.plugin_api_keys
    drop constraint if exists plugin_api_keys_key_prefix_masked_check;
alter table public.plugin_api_keys
    add constraint plugin_api_keys_key_prefix_masked_check
    check (key_prefix ~ '^boss_pk_[A-Za-z0-9]{8}$');

select ok(
    not exists (
        select 1 from public.plugin_api_keys
         where key_hash ~ '^boss_pk_[A-Za-z0-9]{32}$'
            or key_hash !~ '^[0-9a-f]{64}$'
            or key_prefix !~ '^boss_pk_[A-Za-z0-9]{8}$'
    ),
    'no raw key material rests anywhere in the table after the repair'
);

select ok(
    exists (
        select 1
          from pg_catalog.pg_constraint
         where conrelid = 'public.plugin_api_keys'::regclass
           and contype = 'c'
           and conname in ('plugin_api_keys_key_hash_digest_check',
                           'plugin_api_keys_key_prefix_masked_check')
         group by conrelid
        having count(*) = 2
    ),
    'both constraints are in force again after the re-add'
);

select * from finish();
rollback;
