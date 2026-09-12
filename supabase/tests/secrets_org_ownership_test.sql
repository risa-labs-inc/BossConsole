-- pgTAP tests for organisation-owned secrets (migration 20260802000000).
-- Run with: supabase test db
--
-- The secret functions hold the credential vault, so this suite leads with the
-- BACKWARD-COMPATIBILITY pin: a personal secret (org_id IS NULL) must behave
-- exactly as it did before organisations existed. Everything after that is the
-- new organisation behaviour and its boundaries.
--
-- The suite provisions the Vault master key itself, inside the transaction, so it
-- passes on a fresh `supabase db reset` where VAULT_SETUP.md has not been run.
-- The key rolls back with everything else.

begin;
select plan(33);

-- ---------------------------------------------------------------------------
-- Vault key (rolled back). Without it encrypt_text raises and nothing works.
-- ---------------------------------------------------------------------------
select vault.create_secret(
    '2222222222222222222222222222222222222222222222222222222222222222',
    'master_encryption_key',
    'pgTAP test key (transaction-local)');

-- ---------------------------------------------------------------------------
-- Fixtures: an organisation with an owner/admin, a plain member, and an outsider
-- ---------------------------------------------------------------------------
insert into auth.users (id, email, email_confirmed_at) values
    ('50000000-0000-0000-0000-000000000001', 'secadmin@pgtap.test',   now()),
    ('50000000-0000-0000-0000-000000000002', 'secmember@pgtap.test',  now()),
    ('50000000-0000-0000-0000-000000000003', 'secoutside@pgtap.test', now());

select public.create_organisation_internal(
    p_slug=>'pgtsec', p_name=>'PGTap Secrets',
    p_owner_id=>'50000000-0000-0000-0000-000000000001',
    p_visibility=>'private', p_join_policy=>'invite_only');

insert into public.organisation_members (org_id, user_id, status, joined_at, join_source)
select id, '50000000-0000-0000-0000-000000000002', 'active', now(), 'admin'
from public.organisations where slug='pgtsec';

create temporary table t_ids (k text primary key, v uuid);
insert into t_ids select 'org', id from public.organisations where slug='pgtsec';


-- ===========================================================================
-- BACKWARD COMPATIBILITY: a personal secret is untouched by all of this
-- ===========================================================================
select set_config('request.jwt.claims',
    '{"sub":"50000000-0000-0000-0000-000000000002","role":"authenticated"}', true);

insert into t_ids
select 'personal',
       (public.create_secret('github.com','member','pw-personal') ->> 'secret_id')::uuid;

select isnt((select v from t_ids where k='personal'), NULL,
    'COMPAT: create_secret with no p_org_id still creates a personal secret');
select is(
    (select org_id from public.secrets where id=(select v from t_ids where k='personal')),
    NULL, 'COMPAT: a personal secret has org_id NULL');
select is(
    (select password from public.get_user_secrets(50,0) where id=(select v from t_ids where k='personal')),
    'pw-personal', 'COMPAT: get_user_secrets still decrypts the password');
select is(
    (select is_org_owned from public.get_user_secrets(50,0) where id=(select v from t_ids where k='personal')),
    false, 'COMPAT: is_org_owned is false for a personal secret');
select is(
    (select can_manage from public.get_user_secrets(50,0) where id=(select v from t_ids where k='personal')),
    true, 'COMPAT: the creator can manage their personal secret');
select is(
    (select public.create_secret('github.com','member','other') ->> 'error'),
    'A secret for this website and username already exists',
    'COMPAT: the duplicate message is unchanged (unique_personal_secret still raises 23505)');
select is(
    (select access_level from public.get_user_secrets_with_shared(50,0)
      where id=(select v from t_ids where k='personal')),
    'owner', 'COMPAT: get_user_secrets_with_shared still reports access_level owner');


-- ===========================================================================
-- Organisation ownership
-- ===========================================================================
-- A non-member cannot create a secret owned by the organisation.
select set_config('request.jwt.claims',
    '{"sub":"50000000-0000-0000-0000-000000000003","role":"authenticated"}', true);
select is(
    (select public.create_secret('aws.com','root','x',null,null,null,false,null,null,
        (select v from t_ids where k='org')) ->> 'error'),
    'You are not a member of that organisation',
    'a non-member cannot create an organisation-owned secret'
);

-- The plain MEMBER creates one owned by the organisation.
select set_config('request.jwt.claims',
    '{"sub":"50000000-0000-0000-0000-000000000002","role":"authenticated"}', true);
insert into t_ids
select 'orgsecret',
       (public.create_secret('aws.com','deploy','pw-org',null,null,
            array['infra'],false,null,null,
            (select v from t_ids where k='org')) ->> 'secret_id')::uuid;

select isnt((select v from t_ids where k='orgsecret'), NULL,
    'an active member can create an organisation-owned secret');
select is(
    (select org_id from public.secrets where id=(select v from t_ids where k='orgsecret')),
    (select v from t_ids where k='org'),
    'the secret records the organisation as owner');
select is(
    (select user_id from public.secrets where id=(select v from t_ids where k='orgsecret')),
    '50000000-0000-0000-0000-000000000002'::uuid,
    'user_id still records the CREATOR, so the audit trail survives org ownership');

-- Both partial unique indexes.
select is(
    (select public.create_secret('aws.com','deploy','again',null,null,null,false,null,null,
        (select v from t_ids where k='org')) ->> 'error'),
    'A secret for this website and username already exists',
    'unique_org_secret: two organisation rows for the same site+user are refused'
);
select isnt(
    (select public.create_secret('aws.com','deploy','mine') ->> 'secret_id'),
    NULL,
    'the two partial indexes coexist: the same person may hold a PERSONAL secret for a site their organisation also has'
);

-- Visibility: the ADMIN (who did not create it) can read it.
select set_config('request.jwt.claims',
    '{"sub":"50000000-0000-0000-0000-000000000001","role":"authenticated"}', true);
select is(
    (select password from public.get_user_secrets(50,0)
      where id=(select v from t_ids where k='orgsecret')),
    'pw-org', 'an organisation admin reads a colleague''s organisation secret');
select is(
    (select is_org_owned from public.get_user_secrets(50,0)
      where id=(select v from t_ids where k='orgsecret')),
    true, 'is_org_owned is true');
select is(
    (select org_slug from public.get_user_secrets(50,0)
      where id=(select v from t_ids where k='orgsecret')),
    'pgtsec', 'org_slug is projected for the UI');
select is(
    (select can_manage from public.get_user_secrets(50,0)
      where id=(select v from t_ids where k='orgsecret')),
    true, 'the organisation admin CAN manage it');

-- The plain member can read but NOT manage.
select set_config('request.jwt.claims',
    '{"sub":"50000000-0000-0000-0000-000000000002","role":"authenticated"}', true);
select ok(
    public.can_access_secret((select v from t_ids where k='orgsecret')),
    'the creator/member can access it');

-- And an outsider sees nothing at all.
select set_config('request.jwt.claims',
    '{"sub":"50000000-0000-0000-0000-000000000003","role":"authenticated"}', true);
select is(
    (select count(*)::int from public.get_user_secrets(50,0)
      where id=(select v from t_ids where k='orgsecret')),
    0, 'a NON-member does not see the organisation secret at all');
select ok(
    not public.can_access_secret((select v from t_ids where k='orgsecret')),
    'can_access_secret is false for a non-member');
select ok(
    not public.can_manage_secret((select v from t_ids where k='orgsecret')),
    'can_manage_secret is false for a non-member');
select is(
    (select public.delete_secret((select v from t_ids where k='orgsecret')) ->> 'error'),
    'Secret not found or access denied',
    'a non-member cannot delete the organisation secret'
);

-- An organisation ADMIN can delete a colleague's organisation secret.
select set_config('request.jwt.claims',
    '{"sub":"50000000-0000-0000-0000-000000000001","role":"authenticated"}', true);
select is(
    (select public.update_secret((select v from t_ids where k='orgsecret'),
        'aws.com','deploy','rotated') ->> 'success'),
    'true',
    'an organisation admin can UPDATE a colleague''s organisation secret'
);
select is(
    (select password from public.get_user_secrets(50,0)
      where id=(select v from t_ids where k='orgsecret')),
    'rotated', 'the rotation is visible to the organisation');


-- ===========================================================================
-- Organisation as a SHARE target
-- ===========================================================================
-- The outsider owns a personal secret and tries to push it at an organisation
-- they do not belong to.
select set_config('request.jwt.claims',
    '{"sub":"50000000-0000-0000-0000-000000000003","role":"authenticated"}', true);
insert into t_ids
select 'outsider_secret',
       (public.create_secret('evil.test','me','pw-evil') ->> 'secret_id')::uuid;

select is(
    (select public.share_secret((select v from t_ids where k='outsider_secret'),
        null, null, null, null, (select v from t_ids where k='org')) ->> 'error'),
    'You can only share with an organisation you belong to',
    'PHISHING GUARD: you cannot share a secret INTO an organisation you do not belong to'
);

-- A member shares their personal secret with the whole organisation.
select set_config('request.jwt.claims',
    '{"sub":"50000000-0000-0000-0000-000000000002","role":"authenticated"}', true);
select is(
    (select public.share_secret((select v from t_ids where k='personal'),
        null, null, 'team access', null, (select v from t_ids where k='org')) ->> 'target_org'),
    'pgtsec', 'a member can share their own secret with their organisation'
);

-- The admin now sees it via the organisation share.
select set_config('request.jwt.claims',
    '{"sub":"50000000-0000-0000-0000-000000000001","role":"authenticated"}', true);
select is(
    (select shared_with_org_slug from public.get_user_secrets_with_shared(50,0)
      where id=(select v from t_ids where k='personal')),
    'pgtsec',
    'the organisation share surfaces in get_user_secrets_with_shared with the org slug'
);
select is(
    (select is_owner from public.get_user_secrets_with_shared(50,0)
      where id=(select v from t_ids where k='personal')),
    false, 'the recipient is not the owner of a shared secret');

-- get_secret_shares gains the organisation columns.
select set_config('request.jwt.claims',
    '{"sub":"50000000-0000-0000-0000-000000000002","role":"authenticated"}', true);
select is(
    (select shared_with_org_slug from public.get_secret_shares(
        (select v from t_ids where k='personal')) where shared_with_org_id is not null),
    'pgtsec', 'get_secret_shares projects shared_with_org_slug'
);

-- The three-way exactly-one-target rule.
select is(
    (select public.share_secret((select v from t_ids where k='personal'),
        '50000000-0000-0000-0000-000000000003', null, null, null,
        (select v from t_ids where k='org')) ->> 'error'),
    'Must specify exactly one of target_user_id, target_role_id or target_org_id',
    'two targets at once are refused'
);
select is(
    (select public.share_secret((select v from t_ids where k='personal')) ->> 'error'),
    'Must specify exactly one of target_user_id, target_role_id or target_org_id',
    'zero targets are refused'
);
select throws_ok(
    $$ insert into public.secret_shares (secret_id, shared_with_user_id, shared_with_org_id, shared_by)
       values ((select v from t_ids where k='personal'),
               '50000000-0000-0000-0000-000000000003',
               (select v from t_ids where k='org'),
               '50000000-0000-0000-0000-000000000002') $$,
    '23514', NULL,
    'share_target_check rejects two targets even on a direct INSERT'
);

-- Unsharing the organisation target.
select is(
    (select public.unshare_secret((select v from t_ids where k='personal'),
        null, null, (select v from t_ids where k='org')) ->> 'revoked_count'),
    '1', 'unshare_secret removes an organisation share'
);

select * from finish();
rollback;
