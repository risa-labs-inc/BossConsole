-- pgTAP tests for 20261022150000_agent_signin_slots.
-- Run with: supabase test db
begin;
select plan(14);

insert into auth.users (id, email, email_confirmed_at, raw_user_meta_data) values
    ('5a000000-0000-4000-8000-00000000000a', 'owner@slots.test', now(), '{}'),
    ('5a000000-0000-4000-8000-00000000000b', 'outsider@other.test', now(), '{}');
select set_config('request.jwt.claims', '{"role":"service_role"}', true);
select public.create_organisation_internal(p_slug => 'slotsorg', p_name => 'Slots Org', p_description => null,
    p_owner_id => '5a000000-0000-4000-8000-00000000000a', p_domain => 'slots.test', p_visibility => 'private', p_join_policy => 'invite_only');
insert into public.agents (slug, org_id, name, plugin_id, created_by)
values ('slotsagent', (select id from public.organisations where slug = 'slotsorg'), 'Slots', 'ai.rever.boss.plugin.dynamic.agent.slotsagent',
        '5a000000-0000-4000-8000-00000000000a');
create temp table a (id uuid);
grant all on a to authenticated;
insert into a select id from public.agents where slug = 'slotsagent';

-- ---- the mount rule ----
select is(public.validate_agent_brand('{"signin":{"mode":"custom_html","custom_html":"<div><agent-title></agent-title><agent-email-form></agent-email-form></div>"}}'), null, 'a template with one <agent-email-form>');
select is(public.validate_agent_brand('{"signin":{"mode":"custom_html","custom_html":"<p>x</p><agent-signin></agent-signin>"}}'), null, 'the single <agent-signin> still works');
select is(public.validate_agent_brand('{"signin":{"mode":"custom_html","custom_html":"<agent-signin></agent-signin><agent-email-form></agent-email-form>"}}'), 'signin_custom_html_mount_invalid', 'not both');
select is(public.validate_agent_brand('{"signin":{"mode":"custom_html","custom_html":"<agent-email-form></agent-email-form><AGENT-EMAIL-FORM></AGENT-EMAIL-FORM>"}}'), 'signin_custom_html_mount_invalid', 'the email form at most once, any case');
select is(public.validate_agent_brand('{"signin":{"mode":"custom_html","custom_html":"<agent-title></agent-title>"}}'), 'signin_custom_html_mount_invalid', 'the email form is required');
select is(public.validate_agent_brand('{"signin":{"mode":"standard","custom_html":""}}'), null, 'standard needs no template');

-- ---- agent_brand_get / agent_brand_update as the person ----
select ok(not has_function_privilege('anon', 'public.agent_brand_update(uuid,jsonb,text)', 'EXECUTE')
      and not has_function_privilege('anon', 'public.agent_brand_get(uuid)', 'EXECUTE'), 'not anon-callable');
set local role authenticated;
select set_config('request.jwt.claim.sub', '5a000000-0000-4000-8000-00000000000a', true);
select is((public.agent_brand_get((select id from a)))->>'slug', 'slotsagent', 'the owner reads the brand');
select is((public.agent_brand_get((select id from a)))->>'can_edit', 'true', 'with agents.edit');
select is((public.agent_brand_update((select id from a), '{"name":"Slots","signin":{"mode":"standard","layout":"split"}}', 'agent:slotsagent'))->'signin'->>'layout',
    'split', 'the owner saves through the agent');
select throws_ok($q$ select public.agent_brand_update((select id from a), '{"signin":{"mode":"custom_html","custom_html":"<p></p>"}}', 'agent:slotsagent') $q$,
    '23514', 'invalid_brand: signin_custom_html_mount_invalid', 'the validator is the authority');
select throws_ok($q$ select public.agent_brand_update((select id from a), '{}', 'Max!') $q$, '22023', 'via_invalid', 'via is a label, nothing else');
select set_config('request.jwt.claim.sub', '5a000000-0000-4000-8000-00000000000b', true);
select throws_ok($q$ select public.agent_brand_update((select id from a), '{"name":"Hijack"}', 'agent:slotsagent') $q$, '42501', 'agent_not_found', 'an outsider cannot');
reset role;
select is((select row(actor, detail->>'via')::text from public.agent_audit_log where agent_id = (select id from a) and action = 'agent.brand_change' order by id desc limit 1),
    '(5a000000-0000-4000-8000-00000000000a,agent:slotsagent)', 'audited: the person is the actor, via the agent');

select * from finish();
rollback;
