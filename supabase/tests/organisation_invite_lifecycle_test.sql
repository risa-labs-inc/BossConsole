-- pgTAP tests for the organisation invite CONSUME-TIME lifecycle
-- (migration 20260923173000_organisation_invite_lifecycle.sql).
-- Run with: supabase test db
--
-- Mint-time checks are a TOCTOU over the whole life of a link: an invite is a
-- standing grant, and everything it was authorised against -- the inviter's
-- admin status, the role it grants, the org's role set -- can drift between
-- mint and click. These tests hold every drift in place and click.
--
-- Covers: the inviter-authority re-check (demoted, removed, and the LIVE
-- property: re-promoting the inviter re-arms the same link), the role taken
-- ONLY from the stored invite row and re-validated against the org's current
-- role set (mapping deleted post-mint degrades to the default member role, a
-- corrupted admin-kind role_id is refused at consume time), the idempotency
-- ordering surviving the new checks, the enumeration-oracle property, and
-- the OTHER two surfaces that answer the same question -- the landing page's
-- preview and the admin live-list -- agreeing with redemption through the
-- shared organisation_invite_is_live gate.
--
-- The two-session concurrent-accept race is in
-- organisation_invite_concurrency_test.sql.

begin;
select plan(32);

-- ---------------------------------------------------------------------------
-- Fixtures: an organisation, its owner, one admin-by-role (the inviter), and
-- six redeemers. The admin's authority is a user_roles row for the org's
-- admin-kind role, so "demote" and "re-promote" are one INSERT/DELETE away and
-- exercise the LIVE re-check rather than any cached flag.
-- ---------------------------------------------------------------------------
-- email_confirmed_at omitted: it is nullable in a fresh auth schema and the
-- drifted gate DB lacks the column outright; nothing in the invite path
-- depends on it.
insert into auth.users (id, email) values
    ('22000000-0000-0000-0000-000000000001', 'invlif-owner@pgtap.test'),
    ('22000000-0000-0000-0000-000000000002', 'invlif-inviter@pgtap.test'),
    ('22000000-0000-0000-0000-000000000003', 'invlif-u1@pgtap.test'),
    ('22000000-0000-0000-0000-000000000004', 'invlif-u2@pgtap.test'),
    ('22000000-0000-0000-0000-000000000005', 'invlif-u3@pgtap.test'),
    ('22000000-0000-0000-0000-000000000006', 'invlif-u4@pgtap.test'),
    ('22000000-0000-0000-0000-000000000007', 'invlif-u5@pgtap.test'),
    ('22000000-0000-0000-0000-000000000008', 'invlif-u6@pgtap.test');

-- Act as the edge function would for the admin-side calls.
select set_config('request.jwt.claims', '{"role":"service_role"}', true);

select public.create_organisation_internal(
    p_slug=>'pgtinvlife', p_name=>'PGTap Invite Lifecycle',
    p_owner_id=>'22000000-0000-0000-0000-000000000001',
    p_visibility=>'private', p_join_policy=>'invite_only');

-- The inviter: an ordinary member holding the org's admin-kind role. This is
-- the exact authority mint checks, so the consume-time re-check must be the
-- same predicate.
insert into public.organisation_members (org_id, user_id, status, joined_at, join_source)
values ((select id from public.organisations where slug='pgtinvlife'),
        '22000000-0000-0000-0000-000000000002', 'active', now(), 'admin');
insert into public.user_roles (user_id, role_id, assigned_by, assigned_at)
select '22000000-0000-0000-0000-000000000002', orl.role_id,
       '22000000-0000-0000-0000-000000000001', now()
from public.organisation_roles orl
where orl.org_id = (select id from public.organisations where slug='pgtinvlife')
  and orl.kind = 'admin';

-- ===========================================================================
-- SECTION 1: the inviter's authority, re-checked at consume time
-- ===========================================================================
create temporary table t_inv1 (token text, invite_id uuid);
insert into t_inv1
select r ->> 'token', (r ->> 'invite_id')::uuid
from public.create_organisation_invite(
    (select id from public.organisations where slug='pgtinvlife'),
    null, 'demote-me', 1, 24,
    '22000000-0000-0000-0000-000000000002') r;

-- Demote the inviter: their admin-kind grant goes away.
delete from public.user_roles
where user_id = '22000000-0000-0000-0000-000000000002'
  and role_id in (select orl.role_id from public.organisation_roles orl
                   where orl.org_id = (select id from public.organisations where slug='pgtinvlife')
                     and orl.kind = 'admin');

select set_config('request.jwt.claims',
    '{"sub":"22000000-0000-0000-0000-000000000003","role":"authenticated"}', true);
select is(
    (select public.redeem_organisation_invite((select token from t_inv1)) ->> 'error'),
    'Invite link is invalid or expired',
    'a link minted by a since-demoted admin is refused at consume time'
);
select is(
    (select count(*)::int from public.organisation_members m
      where m.org_id = (select id from public.organisations where slug='pgtinvlife')
        and m.user_id = '22000000-0000-0000-0000-000000000003'),
    0,
    'the refused redemption admits nobody'
);
select is(
    (select i.uses from public.organisation_invites i join t_inv1 on t_inv1.invite_id = i.id),
    0,
    'the refused redemption burns nothing'
);

-- THE LIVE PROPERTY: the check is user_is_org_admin NOW, not a snapshot or a
-- revocation flag. Re-promote the inviter and the SAME link, already refused
-- once, works -- no re-mint.
insert into public.user_roles (user_id, role_id, assigned_by, assigned_at)
select '22000000-0000-0000-0000-000000000002', orl.role_id,
       '22000000-0000-0000-0000-000000000001', now()
from public.organisation_roles orl
where orl.org_id = (select id from public.organisations where slug='pgtinvlife')
  and orl.kind = 'admin';

select is(
    (select public.redeem_organisation_invite((select token from t_inv1)) ->> 'success'),
    'true',
    're-promoting the inviter re-arms the SAME link -- the check is live, not a flag'
);
select is(
    (select m.status from public.organisation_members m
      where m.org_id = (select id from public.organisations where slug='pgtinvlife')
        and m.user_id = '22000000-0000-0000-0000-000000000003'),
    'active',
    'the re-armed link admits the redeemer'
);
select is(
    (select i.uses from public.organisation_invites i join t_inv1 on t_inv1.invite_id = i.id),
    1,
    'the re-armed link burns its single use'
);

-- Removed inviter. Mint while still admin, then remove entirely --
-- remove_organisation_member also deletes their user_roles rows for this org,
-- so it is the strongest form of the same drift.
select set_config('request.jwt.claims', '{"role":"service_role"}', true);
create temporary table t_inv2 (token text, invite_id uuid);
insert into t_inv2
select r ->> 'token', (r ->> 'invite_id')::uuid
from public.create_organisation_invite(
    (select id from public.organisations where slug='pgtinvlife'),
    null, 'remove-me', null, 24,
    '22000000-0000-0000-0000-000000000002') r;

select public.remove_organisation_member(
    (select id from public.organisations where slug='pgtinvlife'),
    '22000000-0000-0000-0000-000000000002',
    '22000000-0000-0000-0000-000000000001');

select set_config('request.jwt.claims',
    '{"sub":"22000000-0000-0000-0000-000000000004","role":"authenticated"}', true);
select is(
    (select public.redeem_organisation_invite((select token from t_inv2)) ->> 'error'),
    'Invite link is invalid or expired',
    'a link minted by a since-REMOVED admin is refused -- the same message, no oracle'
);
select is(
    (select count(*)::int from public.organisation_members m
      where m.org_id = (select id from public.organisations where slug='pgtinvlife')
        and m.user_id = '22000000-0000-0000-0000-000000000004'),
    0,
    'the removed-inviter link admits nobody'
);
select is(
    (select i.uses from public.organisation_invites i join t_inv2 on t_inv2.invite_id = i.id),
    0,
    'the removed-inviter link burns nothing'
);

-- ===========================================================================
-- SECTION 2: control -- the re-check does not over-tighten. The OWNER's links
-- (and any still-admin inviter's) are unaffected.
-- ===========================================================================
select set_config('request.jwt.claims', '{"role":"service_role"}', true);
create temporary table t_inv3 (token text, invite_id uuid);
insert into t_inv3
select r ->> 'token', (r ->> 'invite_id')::uuid
from public.create_organisation_invite(
    (select id from public.organisations where slug='pgtinvlife'),
    null, 'owners-link', null, 24,
    '22000000-0000-0000-0000-000000000001') r;

select set_config('request.jwt.claims',
    '{"sub":"22000000-0000-0000-0000-000000000005","role":"authenticated"}', true);
select is(
    (select public.redeem_organisation_invite((select token from t_inv3)) ->> 'success'),
    'true',
    'control: an owner-minted link still redeems'
);
select is(
    (select m.status from public.organisation_members m
      where m.org_id = (select id from public.organisations where slug='pgtinvlife')
        and m.user_id = '22000000-0000-0000-0000-000000000005'),
    'active',
    'control: the owner-minted redeemer is an active member'
);

-- ===========================================================================
-- SECTION 3: the role comes from the stored invite row and is re-validated
-- against the organisation's CURRENT role set.
-- ===========================================================================
select set_config('request.jwt.claims', '{"role":"service_role"}', true);
create temporary table t_dev as
select (r ->> 'role_id')::uuid as role_id
from public.create_organisation_role(
    (select id from public.organisations where slug='pgtinvlife'),
    'dev', null, '22000000-0000-0000-0000-000000000001') r;

create temporary table t_inv4 (token text, invite_id uuid);
insert into t_inv4
select r ->> 'token', (r ->> 'invite_id')::uuid
from public.create_organisation_invite(
    (select id from public.organisations where slug='pgtinvlife'),
    (select role_id from t_dev), 'dev-link', null, 24,
    '22000000-0000-0000-0000-000000000001') r;

select set_config('request.jwt.claims',
    '{"sub":"22000000-0000-0000-0000-000000000006","role":"authenticated"}', true);
select is(
    (select public.redeem_organisation_invite((select token from t_inv4)) ->> 'success'),
    'true',
    'a role-bearing link redeems'
);
select is(
    (select count(*)::int from public.user_roles ur
      join public.organisation_roles orl on orl.role_id = ur.role_id
     where orl.org_id = (select id from public.organisations where slug='pgtinvlife')
       and ur.user_id = '22000000-0000-0000-0000-000000000006'),
    1,
    'the redeemer holds EXACTLY ONE role of this organisation'
);
select is(
    (select count(*)::int from public.user_roles
      where user_id = '22000000-0000-0000-0000-000000000006'
        and role_id = (select role_id from t_dev)),
    1,
    'it is the STORED invite role -- accept takes no role parameter to tamper with'
);
select is(
    (select count(*)::int from public.user_roles ur
      join public.organisation_roles orl on orl.role_id = ur.role_id
     where orl.org_id = (select id from public.organisations where slug='pgtinvlife')
       and orl.kind = 'admin'
       and ur.user_id = '22000000-0000-0000-0000-000000000006'),
    0,
    'a dev-role link never grants the admin-kind role'
);

-- The mapping row is deleted AFTER mint (delete_organisation_role deletes the
-- roles row itself, which ON DELETE SET NULLs invite.role_id -- this drift,
-- reachable only by direct table access or future code, must degrade to the
-- default member role, never grant an orphaned global role row).
select set_config('request.jwt.claims', '{"role":"service_role"}', true);
create temporary table t_inv5 (token text, invite_id uuid);
insert into t_inv5
select r ->> 'token', (r ->> 'invite_id')::uuid
from public.create_organisation_invite(
    (select id from public.organisations where slug='pgtinvlife'),
    (select role_id from t_dev), 'orphaned-role-link', null, 24,
    '22000000-0000-0000-0000-000000000001') r;

delete from public.organisation_roles
where org_id = (select id from public.organisations where slug='pgtinvlife')
  and role_id = (select role_id from t_dev);

select set_config('request.jwt.claims',
    '{"sub":"22000000-0000-0000-0000-000000000007","role":"authenticated"}', true);
select is(
    (select public.redeem_organisation_invite((select token from t_inv5)) ->> 'success'),
    'true',
    'the join still happens even when the invited role has drifted'
);
select is(
    (select count(*)::int from public.organisation_members m
      where m.org_id = (select id from public.organisations where slug='pgtinvlife')
        and m.user_id = '22000000-0000-0000-0000-000000000007'
        and m.status = 'active'),
    1,
    'the redeemer of the drifted link becomes an active member'
);
select is(
    (select count(*)::int from public.user_roles
      where user_id = '22000000-0000-0000-0000-000000000007'
        and role_id = (select role_id from t_dev)),
    0,
    'an unmapped role is never granted -- no orphaned global role row'
);
select is(
    (select count(*)::int from public.user_roles ur
      join public.organisation_roles orl on orl.role_id = ur.role_id
     where orl.org_id = (select id from public.organisations where slug='pgtinvlife')
       and orl.kind = 'user'
       and ur.user_id = '22000000-0000-0000-0000-000000000007'),
    1,
    'the drifted link degrades to the default member role, like ON DELETE SET NULL'
);

-- A corrupted row: role_id forced onto the organisation's ADMIN role -- the
-- exact state mint refuses to create. Consume time must refuse it too: the
-- join happens, the takeover grant does not.
select set_config('request.jwt.claims', '{"role":"service_role"}', true);
create temporary table t_inv6 (token text, invite_id uuid);
insert into t_inv6
select r ->> 'token', (r ->> 'invite_id')::uuid
from public.create_organisation_invite(
    (select id from public.organisations where slug='pgtinvlife'),
    null, 'corrupted-link', null, 24,
    '22000000-0000-0000-0000-000000000001') r;

update public.organisation_invites
   set role_id = (select orl.role_id from public.organisation_roles orl
                   where orl.org_id = (select id from public.organisations where slug='pgtinvlife')
                     and orl.kind = 'admin')
 where id = (select invite_id from t_inv6);

select set_config('request.jwt.claims',
    '{"sub":"22000000-0000-0000-0000-000000000008","role":"authenticated"}', true);
select is(
    (select public.redeem_organisation_invite((select token from t_inv6)) ->> 'success'),
    'true',
    'the corrupted link still JOINS -- mint says to assign admin explicitly AFTER they join'
);
select is(
    (select count(*)::int from public.user_roles ur
      join public.organisation_roles orl on orl.role_id = ur.role_id
     where orl.org_id = (select id from public.organisations where slug='pgtinvlife')
       and orl.kind = 'admin'
       and ur.user_id = '22000000-0000-0000-0000-000000000008'),
    0,
    'CONSUME-TIME REFUSAL: an admin-kind role_id is never granted through a link'
);
select is(
    (select count(*)::int from public.user_roles ur
      join public.organisation_roles orl on orl.role_id = ur.role_id
     where orl.org_id = (select id from public.organisations where slug='pgtinvlife')
       and orl.kind = 'user'
       and ur.user_id = '22000000-0000-0000-0000-000000000008'),
    1,
    'the corrupted link degrades to the default member role'
);

-- ===========================================================================
-- SECTION 4: the ordering property survives. The demoted/removed inviter's
-- dead link must not lie to the member it already admitted.
-- ===========================================================================
-- inv1's creator was removed in section 1, and inv1 is exhausted (uses = 1).
-- u1 is still an active member, so their re-click must say already_member --
-- idempotency outranks validity AND the inviter re-check, exactly as it
-- outranks revoked/expired/exhausted.
select set_config('request.jwt.claims',
    '{"sub":"22000000-0000-0000-0000-000000000003","role":"authenticated"}', true);
select is(
    (select public.redeem_organisation_invite((select token from t_inv1)) ->> 'already_member'),
    'true',
    'idempotency outranks the inviter re-check: the already-admitted member still hears already_member'
);
select is(
    (select i.uses from public.organisation_invites i join t_inv1 on t_inv1.invite_id = i.id),
    1,
    'the re-click burns nothing'
);

-- ===========================================================================
-- SECTION 5: the SAME gate on every surface. The landing page's preview and
-- the admin live-list answer "does this link still admit?" with the same
-- shared predicate redemption refuses by -- organisation_invite_is_live --
-- so a demoted inviter's link stops being advertised the same hour it starts
-- being refused, and a link redemption still ADMITS (a drifted role
-- degrades the grant, it does not kill the join) stays valid on the page.
-- ===========================================================================
-- Re-establish the inviter as an admin-by-role: section 1 removed them, and
-- the surfaces need a mint whose authority can be WITHDRAWN live.
select set_config('request.jwt.claims', '{"role":"service_role"}', true);
insert into public.organisation_members (org_id, user_id, status, joined_at, join_source)
values ((select id from public.organisations where slug='pgtinvlife'),
        '22000000-0000-0000-0000-000000000002', 'active', now(), 'admin');
insert into public.user_roles (user_id, role_id, assigned_by, assigned_at)
select '22000000-0000-0000-0000-000000000002', orl.role_id,
       '22000000-0000-0000-0000-000000000001', now()
from public.organisation_roles orl
where orl.org_id = (select id from public.organisations where slug='pgtinvlife')
  and orl.kind = 'admin';

create temporary table t_inv7 (token text, invite_id uuid);
insert into t_inv7
select r ->> 'token', (r ->> 'invite_id')::uuid
from public.create_organisation_invite(
    (select id from public.organisations where slug='pgtinvlife'),
    null, 'preview-me', null, 24,
    '22000000-0000-0000-0000-000000000002') r;

select is(
    (select public.get_organisation_invite_preview((select token from t_inv7)) ->> 'valid'),
    'true',
    'control: an admin inviter''s link previews as valid'
);
select is(
    (select (value ->> 'is_live')::boolean
       from jsonb_array_elements(
            (select public.list_organisation_invites(
                (select id from public.organisations where slug='pgtinvlife'),
                '22000000-0000-0000-0000-000000000001') -> 'data')) as value
      where value ->> 'label' = 'preview-me'),
    true,
    'control: the admin live-list shows the admin inviter''s link as live'
);

-- Demote the inviter: redemption refuses (section 1), and the two display
-- surfaces must say so too -- the page must not advertise a dead link, and
-- the admin pill must not show "live" for one.
delete from public.user_roles
where user_id = '22000000-0000-0000-0000-000000000002'
  and role_id in (select orl.role_id from public.organisation_roles orl
                   where orl.org_id = (select id from public.organisations where slug='pgtinvlife')
                     and orl.kind = 'admin');

select is(
    (select public.get_organisation_invite_preview((select token from t_inv7)) ->> 'valid'),
    'false',
    'the demoted inviter''s link previews as INVALID -- the page redemption would refuse'
);
select is(
    (select (value ->> 'is_live')::boolean
       from jsonb_array_elements(
            (select public.list_organisation_invites(
                (select id from public.organisations where slug='pgtinvlife'),
                '22000000-0000-0000-0000-000000000001') -> 'data')) as value
      where value ->> 'label' = 'preview-me'),
    false,
    'the admin live-list flips the demoted inviter''s link to not-live'
);

-- Re-promote: the shared predicate is LIVE, so both surfaces re-arm the same
-- link, exactly as section 1 showed redemption does.
insert into public.user_roles (user_id, role_id, assigned_by, assigned_at)
select '22000000-0000-0000-0000-000000000002', orl.role_id,
       '22000000-0000-0000-0000-000000000001', now()
from public.organisation_roles orl
where orl.org_id = (select id from public.organisations where slug='pgtinvlife')
  and orl.kind = 'admin';

select is(
    (select public.get_organisation_invite_preview((select token from t_inv7)) ->> 'valid'),
    'true',
    're-promoting the inviter re-arms the link on the PAGE too -- the gate is live, not a flag'
);
select is(
    (select (value ->> 'is_live')::boolean
       from jsonb_array_elements(
            (select public.list_organisation_invites(
                (select id from public.organisations where slug='pgtinvlife'),
                '22000000-0000-0000-0000-000000000001') -> 'data')) as value
      where value ->> 'label' = 'preview-me'),
    true,
    're-promoting the inviter re-arms the link in the LIST too'
);

-- The role re-validation is NOT in the shared predicate, on purpose: sections
-- 3 proved redemption still ADMITS these links (the join happens, only the
-- grant degrades), so the preview must still call them valid.
select is(
    (select public.get_organisation_invite_preview((select token from t_inv5)) ->> 'valid'),
    'true',
    'a drifted-role link still previews as valid -- redemption still admits it, only the granted role degrades'
);
select is(
    (select public.get_organisation_invite_preview((select token from t_inv6)) ->> 'valid'),
    'true',
    'the corrupted admin-role link also previews as valid -- the join still happens; the admin grant is what consume time refuses'
);

select * from finish();
rollback;
