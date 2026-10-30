-- Marketplace submission cap: 20 a day for ordinary users; none for platform admins or trusted publishers.
begin;
select plan(6);

insert into auth.users (id, email, email_confirmed_at) values
    ('c7000000-0000-4000-8000-0000000000a1', 'plain@cap.test', now()),
    ('c7000000-0000-4000-8000-0000000000a2', 'trusted@cap.test', now()),
    ('c7000000-0000-4000-8000-0000000000c1', 'admin@cap.test', now());
insert into public.user_roles (user_id, role_id)
    select 'c7000000-0000-4000-8000-0000000000c1', id from public.roles where name = 'admin';
insert into public.marketplace_trusted_publishers (user_id, granted_by)
    values ('c7000000-0000-4000-8000-0000000000a2', 'c7000000-0000-4000-8000-0000000000c1');

create function pg_temp.as_user(p text) returns void language sql as $$
    select set_config('request.jwt.claim.sub', ('c7000000-0000-4000-8000-0000000000' || p), true),
           set_config('request.jwt.claims', json_build_object('role', 'authenticated', 'sub', 'c7000000-0000-4000-8000-0000000000' || p)::text, true);
$$;
-- Submits n distinct skills as the current user; returns how many went through.
create function pg_temp.submit_many(who text, n int) returns int language plpgsql as $$
declare ok int := 0; i int;
begin
    for i in 1..n loop
        begin
            perform public.marketplace_submit('skill', who || '-cap-' || i, 'Cap ' || i, 'Summary.', '', 'Pub',
                jsonb_build_object('markdown', 'Step ' || i || '.'));
            ok := ok + 1;
        exception when others then
            if sqlerrm <> 'submission_rate_limited' then raise; end if;
        end;
    end loop;
    return ok;
end $$;
grant execute on function pg_temp.as_user(text), pg_temp.submit_many(text, int) to authenticated;

set local role authenticated;
select pg_temp.as_user('a1');
select is(pg_temp.submit_many('plain', 20), 20, 'an ordinary user can submit 20 in a day');
select throws_ok($$ select public.marketplace_submit('skill', 'plain-cap-21', 'Cap 21', 'S.', '', 'Pub', jsonb_build_object('markdown', 'x')) $$,
    'P0001', 'submission_rate_limited', 'the 21st is refused');

select pg_temp.as_user('a2');
select is(pg_temp.submit_many('trusted', 25), 25, 'a trusted publisher has no daily cap');

select pg_temp.as_user('c1');
select is(pg_temp.submit_many('admin', 25), 25, 'a platform admin has no daily cap');
select is(public.marketplace_can_skip_review(), true, 'the cap follows marketplace_can_skip_review');

select pg_temp.as_user('a1');
select throws_ok($$ select public.marketplace_submit('skill', 'plain-cap-22', 'Cap 22', 'S.', '', 'Pub', jsonb_build_object('markdown', 'x')) $$,
    'P0001', 'submission_rate_limited', 'still capped for the ordinary user');

select * from finish();
rollback;
