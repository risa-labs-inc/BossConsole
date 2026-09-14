-- The one rule for "may this account learn who that account is".
--
-- Lives here, at the platform layer, because more than one feature needs it:
-- the BOSS Arcade leaderboards and opponent picker, and poker table chat. Both
-- previously answered it with `using (true)` / no filter at all, which in this
-- project means "every account", and signup is open - so it meant the internet
-- once a stranger registered. A second copy of this rule in each feature is the
-- failure mode being fixed, not an acceptable cost.
--
-- Membership of an organisation counts as evidence that two people know each
-- other only if a human approved the join. `is_system` excludes the catch-all
-- `boss` org that every account joins on signup (153 members across 20 email
-- domains, so shared membership there means nothing); `join_policy <> 'open'`
-- excludes any future self-serve org for the same reason. `visibility` is
-- deliberately NOT part of the test: it governs whether an org can be
-- discovered, not how members are admitted, and conflating the two would hide a
-- public-but-invite-only org for no reason.
create or replace function public.org_is_vetted(p_org uuid)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
  select exists (
    select 1
    from public.organisations o
    where o.id = p_org
      and not o.is_system
      and o.join_policy <> 'open'
  );
$$;

-- The SET of users the caller may see: themselves, plus everyone sharing an
-- active membership in a vetted organisation.
--
-- Returned as a set, not as a per-target boolean, because that is the form read
-- paths need: as a per-row predicate the same rule was pushed below a
-- DISTINCT ON and evaluated once per underlying row - 2.7s on a 45-player
-- leaderboard over 29k score rows, versus 37ms for the set. Use the set in
-- policies and read queries. This migration does not change write-path
-- authorization; direct sharing to a known user UUID retains its contract.
--
-- Signed out yields the empty set, via an explicit `is not null` arm rather
-- than a NULL comparison happening to match nothing. That distinction matters:
-- relying on `x <> NULL` is what made the Arcade's opponent picker LOOK safe to
-- anonymous callers while it was in fact still anon-callable.
create or replace function public.org_visible_users()
returns setof uuid
language sql
stable
security definer
set search_path = public
as $$
  select auth.uid() where auth.uid() is not null
  union
  select theirs.user_id
  from public.organisation_members mine
  join public.organisation_members theirs on theirs.org_id = mine.org_id
  where mine.user_id = auth.uid()
    and mine.status = 'active'
    and theirs.status = 'active'
    and public.org_is_vetted(mine.org_id);
$$;

-- `authenticated` needs EXECUTE explicitly, not by inheritance: an RLS policy
-- expression is evaluated as the querying role, so a policy that calls
-- org_visible_users() fails for a role that cannot execute it. Granting it is
-- safe by construction - the function returns only the caller's own visible
-- set, so it discloses nothing the caller could not already derive.
revoke all on function public.org_is_vetted(uuid) from public, anon;
revoke all on function public.org_visible_users() from public, anon;
grant execute on function public.org_visible_users() to authenticated;

-- The one display-name rule, for the same reason: it existed as nine near-copies
-- (six in the Arcade SQL, arcade_admin_requests, poker_leaderboard,
-- poker_lobby), and three of them disagreed about whether the metadata key is
-- 'full_name' or 'display_name', so the same person could appear under two
-- names on two boards. All three keys are accepted here so no site loses a name
-- it used to show.
--
-- The email local part is the last resort. For all 45 current Arcade players it
-- is the ONLY name available - raw_user_meta_data carries no name at all - so an
-- opaque handle would empty every leaderboard. That is acceptable only because
-- org_visible_users now bounds who can reach this function's output. If a real
-- profile name ever lands, drop the split_part arm here and nowhere else.
create or replace function public.user_display_name(p_user uuid)
returns text
language sql
stable
security definer
set search_path = public
as $$
  select coalesce(
    u.raw_user_meta_data ->> 'full_name',
    u.raw_user_meta_data ->> 'display_name',
    u.raw_user_meta_data ->> 'name',
    split_part(u.email, '@', 1)
  )
  from auth.users u
  where u.id = p_user;
$$;

-- Called only from inside SECURITY DEFINER functions, which run as owner, so no
-- client role needs EXECUTE. Unlike org_visible_users, this one is NOT safe to
-- expose: it answers for any uuid, so a client grant would turn it into a
-- name-for-uuid oracle that bypasses the visibility rule entirely.
revoke all on function public.user_display_name(uuid) from public, anon, authenticated;
