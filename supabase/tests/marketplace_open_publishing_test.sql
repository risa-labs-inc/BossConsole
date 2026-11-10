-- Marketplace open publishing: platform flag 'marketplace_open_publishing' lets every signed-in publisher go live without
-- review (as 'unreviewed'). Off by default, admin-only, fails closed, keeps the daily cap.
begin;
select plan(19);

-- a1 ordinary publisher, a2 ordinary publisher (cap), c1 platform admin.
insert into auth.users (id, email, email_confirmed_at) values
    ('c8000000-0000-4000-8000-0000000000a1', 'plain@open.test', now()),
    ('c8000000-0000-4000-8000-0000000000a2', 'cap@open.test', now()),
    ('c8000000-0000-4000-8000-0000000000c1', 'admin@open.test', now());
insert into public.user_roles (user_id, role_id)
    select 'c8000000-0000-4000-8000-0000000000c1', id from public.roles where name = 'admin';

create function pg_temp.as_user(p text) returns void language sql as $$
    select set_config('request.jwt.claim.sub', ('c8000000-0000-4000-8000-0000000000' || p), true),
           set_config('request.jwt.claims', json_build_object('role', 'authenticated', 'sub', 'c8000000-0000-4000-8000-0000000000' || p)::text, true);
$$;
create function pg_temp.sub(slug text, skip boolean default false) returns jsonb language sql as $$
    select public.marketplace_submit('skill', slug, 'Open ' || slug, 'Summary.', '', 'Pub',
        jsonb_build_object('markdown', 'Do ' || slug || '.'), skip) $$;
create function pg_temp.submit_many(who text, n int) returns int language plpgsql as $$
declare ok int := 0; i int;
begin
    for i in 1..n loop
        begin
            perform pg_temp.sub(who || '-cap-' || i);
            ok := ok + 1;
        exception when others then
            if sqlerrm <> 'submission_rate_limited' then raise; end if;
        end;
    end loop;
    return ok;
end $$;
grant execute on function pg_temp.as_user(text), pg_temp.sub(text, boolean), pg_temp.submit_many(text, int) to authenticated;

select is((select value from public.platform_flags where key = 'marketplace_open_publishing'), 'false'::jsonb,
    'the flag ships off');

set local role authenticated;
select pg_temp.as_user('a1');
select is(public.marketplace_open_publishing(), false, 'open publishing is off');
select is(pg_temp.sub('off-skill') ->> 'status', 'pending_review', 'off: an ordinary submission waits for review');
select throws_ok($$ select pg_temp.sub('off-skip', true) $$, '42501', 'review_skip_not_allowed',
    'off: an ordinary user cannot ask to skip review');

-- Only a platform admin can turn it on.
update public.platform_flags set value = 'true'::jsonb where key = 'marketplace_open_publishing';
select is(public.marketplace_open_publishing(), false, 'an ordinary user cannot turn it on');
select pg_temp.as_user('c1');
update public.platform_flags set value = 'true'::jsonb where key = 'marketplace_open_publishing';
select is(public.marketplace_open_publishing(), true, 'a platform admin turns it on');

select pg_temp.as_user('a1');
select is(pg_temp.sub('open-skill') - 'version', '{"slug":"open-skill","status":"live","reviewed":false}'::jsonb,
    'on: an ordinary submission is live at once, not reviewed');
select is(public.marketplace_get('open-skill') ->> 'reviewed', 'false', 'the listing reads as not reviewed');
select is(public.marketplace_get('open-skill') -> 'live' ->> 'decision', 'unreviewed', 'its live version is unreviewed');
select is(jsonb_path_exists(public.marketplace_search('open-skill'), '$[*] ? (@.slug == "open-skill")'), true,
    'it is listed in search');
select is(pg_temp.sub('open-skip', true) ->> 'status', 'live', 'on: asking to skip review is allowed');
select is(pg_temp.sub('off-skill') ->> 'status', 'live', 'on: a listing that was waiting goes live with its next version');

select pg_temp.as_user('a2');
select is(pg_temp.submit_many('cap', 21), 20, 'on: the 20-a-day cap still applies to an ordinary user');

select pg_temp.as_user('c1');
select is(public.marketplace_mark_reviewed('open-skill') ->> 'reviewed', 'true', 'an admin can still mark it reviewed');

reset role;
select is((select count(*)::int from public.platform_audit_log
           where action = 'marketplace.publish_unreviewed' and actor = 'c8000000-0000-4000-8000-0000000000a1'), 3,
    'each open publish is in the platform audit log');
select is((select count(*)::int from public.platform_audit_log
           where action = 'platform.flag_set' and detail ->> 'key' = 'marketplace_open_publishing'
             and actor = 'c8000000-0000-4000-8000-0000000000c1'), 1,
    'turning it on is in the platform audit log');

update public.platform_flags set value = 'false'::jsonb where key = 'marketplace_open_publishing';
set local role authenticated;
select pg_temp.as_user('a1');
select is(pg_temp.sub('off-again') ->> 'status', 'pending_review', 'off again: submissions wait for review');

reset role;
delete from public.platform_flags where key = 'marketplace_open_publishing';
set local role authenticated;
select pg_temp.as_user('a1');
select is(public.marketplace_open_publishing(), false, 'fails closed when the flag row is missing');
select is(pg_temp.sub('no-row') ->> 'status', 'pending_review', 'and submissions wait for review');

select * from finish();
rollback;
