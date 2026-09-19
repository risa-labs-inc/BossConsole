-- pgTAP tests for get_user_secret_by_id (migration 20260919000000): one row,
-- decrypted, under exactly get_user_secrets' visibility and shape.
-- Run with: supabase test db
--
-- The suite provisions the Vault master key itself, inside the transaction, so
-- it passes on a fresh `supabase db reset`. The key rolls back with the rest.

begin;
select plan(16);

DO $fixture$
DECLARE existing uuid;
BEGIN
    SELECT id INTO existing FROM vault.secrets WHERE name = 'master_encryption_key';
    IF existing IS NULL THEN
        PERFORM vault.create_secret('byid-test-key-0123456789abcdef0123456', 'master_encryption_key', 'pgTAP only');
    ELSE
        PERFORM vault.update_secret(existing, 'byid-test-key-0123456789abcdef0123456', 'master_encryption_key', 'pgTAP only');
    END IF;
END;
$fixture$;

-- ---------------------------------------------------------------------------
-- Fixtures: an org with an admin and a member, an outsider, and four secrets:
-- the member's personal one, the org's, the outsider's, and a corrupt one.
-- ---------------------------------------------------------------------------
insert into auth.users (id, email, email_confirmed_at) values
    ('61000000-0000-4000-8000-000000000001', 'byid-admin@pgtap.test',    now()),
    ('61000000-0000-4000-8000-000000000002', 'byid-member@pgtap.test',   now()),
    ('61000000-0000-4000-8000-000000000003', 'byid-outsider@pgtap.test', now());

select public.create_organisation_internal(
    p_slug=>'pgtbyid', p_name=>'PGTap ById',
    p_owner_id=>'61000000-0000-4000-8000-000000000001',
    p_visibility=>'private', p_join_policy=>'invite_only');

insert into public.organisation_members (org_id, user_id, status, joined_at, join_source)
select id, '61000000-0000-4000-8000-000000000002', 'active', now(), 'admin'
from public.organisations where slug='pgtbyid';

insert into public.secrets (id, user_id, org_id, website, username, password_encrypted, notes) values
    ('61000000-0000-4000-8000-000000000011', '61000000-0000-4000-8000-000000000002', NULL,
        'github.com', 'member', public.encrypt_text('pw-personal'), 'personal note'),
    ('61000000-0000-4000-8000-000000000012', '61000000-0000-4000-8000-000000000001',
        (select id from public.organisations where slug='pgtbyid'),
        'registry.example', 'deploy', public.encrypt_text('pw-org'), NULL),
    ('61000000-0000-4000-8000-000000000013', '61000000-0000-4000-8000-000000000003', NULL,
        'github.com', 'outsider', public.encrypt_text('pw-outsider'), NULL),
    ('61000000-0000-4000-8000-000000000014', '61000000-0000-4000-8000-000000000002', NULL,
        'corrupt.example', 'member', 'not even base64!!', NULL);

insert into public.secret_tags (secret_id, tag) values
    ('61000000-0000-4000-8000-000000000011', 'ai-provider'),
    ('61000000-0000-4000-8000-000000000011', 'ci');

-- ---------------------------------------------------------------------------
-- Grants: the listing RPCs' client roles, nothing wider.
-- ---------------------------------------------------------------------------
select ok(has_function_privilege('authenticated', 'public.get_user_secret_by_id(uuid)', 'EXECUTE'),
    'authenticated may call get_user_secret_by_id');
select ok(has_function_privilege('service_role', 'public.get_user_secret_by_id(uuid)', 'EXECUTE'),
    'service_role may call get_user_secret_by_id');
select ok(not has_function_privilege('anon', 'public.get_user_secret_by_id(uuid)', 'EXECUTE'),
    'anon may not call get_user_secret_by_id');
select ok(
    (select coalesce(p.proconfig @> ARRAY['search_path=""']::text[], false)
       from pg_proc p join pg_namespace n on n.oid = p.pronamespace
      where n.nspname = 'public' and p.proname = 'get_user_secret_by_id'),
    'get_user_secret_by_id pins search_path to empty');

-- ---------------------------------------------------------------------------
-- As the member.
-- ---------------------------------------------------------------------------
select set_config('request.jwt.claims',
    '{"sub":"61000000-0000-4000-8000-000000000002","role":"authenticated"}', true);
set local role authenticated;

select is(
    (select password from public.get_user_secret_by_id('61000000-0000-4000-8000-000000000011')),
    'pw-personal', 'the owner gets their personal secret decrypted');
select is(
    (select tags from public.get_user_secret_by_id('61000000-0000-4000-8000-000000000011')),
    '["ai-provider", "ci"]'::jsonb, 'tags are aggregated exactly as the listing aggregates them');
select results_eq(
    $$ select * from public.get_user_secret_by_id('61000000-0000-4000-8000-000000000011') $$,
    $$ select * from public.get_user_secrets(50, 0) where id = '61000000-0000-4000-8000-000000000011' $$,
    'the row is the listing''s row, column for column');
select is(
    (select password from public.get_user_secret_by_id('61000000-0000-4000-8000-000000000012')),
    'pw-org', 'an org member resolves the org''s secret, as the listing shows it to them');
select results_eq(
    $$ select * from public.get_user_secret_by_id('61000000-0000-4000-8000-000000000012') $$,
    $$ select * from public.get_user_secrets(50, 0) where id = '61000000-0000-4000-8000-000000000012' $$,
    'the org row is the listing''s row too, is_org_owned and can_manage included');
select is(
    (select count(*) from public.get_user_secret_by_id('61000000-0000-4000-8000-000000000013')),
    0::bigint, 'another user''s secret is an empty result, not an error');
select is(
    (select count(*) from public.get_user_secret_by_id('61000000-0000-4000-8000-000000000099')),
    0::bigint, 'an unknown id is an empty result, indistinguishable from not-yours');
select is(
    (select password from public.get_user_secret_by_id('61000000-0000-4000-8000-000000000014')),
    ''::text, 'a corrupt row is blanked, not raised (fail-soft parity with the listing)');
select lives_ok(
    $$ select * from public.get_user_secret_by_id('61000000-0000-4000-8000-000000000014') $$,
    'a corrupt row does not abort the call');

-- ---------------------------------------------------------------------------
-- As the outsider: the same ids, the other way round.
-- ---------------------------------------------------------------------------
select set_config('request.jwt.claims',
    '{"sub":"61000000-0000-4000-8000-000000000003","role":"authenticated"}', true);

select is(
    (select password from public.get_user_secret_by_id('61000000-0000-4000-8000-000000000013')),
    'pw-outsider', 'the outsider gets their own secret');
select is(
    (select count(*) from public.get_user_secret_by_id('61000000-0000-4000-8000-000000000012')),
    0::bigint, 'a non-member cannot resolve the org''s secret by id');
select is(
    (select count(*) from public.get_user_secret_by_id('61000000-0000-4000-8000-000000000011')),
    0::bigint, 'a non-owner cannot resolve a personal secret by id');

select * from finish();
rollback;
