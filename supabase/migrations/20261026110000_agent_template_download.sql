-- The canonical unlisted BOSS adapter template is a build dependency for authorised org publishers.
-- Other unlisted/private plugins retain their existing organisation and install gates.
CREATE OR REPLACE FUNCTION "public"."user_can_install_plugin"(
    "p_user_id" "uuid",
    "p_plugin_id" "uuid"
) RETURNS boolean
    LANGUAGE "sql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
    SELECT EXISTS (
        SELECT 1
        FROM public.plugins p
        WHERE p.id = p_plugin_id
          AND (
              public.user_can_view_plugin_row(
                  p_user_id, p.visibility, p.org_id, p.author_id, p.published)
              OR (
                  p.visibility = 'unlisted'
                  AND p.published
                  AND p_user_id IS NOT NULL
                  AND public.user_is_org_member(p_user_id, p.org_id)
              )
              OR (
                  p.plugin_id = 'ai.rever.boss.plugin.dynamic.agentadaptertemplate'
                  AND p.visibility = 'unlisted' AND p.published
                  AND p_user_id IS NOT NULL
                  AND EXISTS (SELECT 1 FROM public.organisations publisher
                      WHERE publisher.id = p.org_id AND publisher.slug = 'boss' AND publisher.is_system)
                  AND EXISTS (SELECT 1 FROM public.organisation_members membership
                      WHERE membership.user_id = p_user_id AND membership.status = 'active'
                        AND public.user_has_org_permission(p_user_id, membership.org_id, 'agents.publish'))
              )
              OR (
                  p.published
                  AND p_user_id IS NOT NULL
                  AND EXISTS (
                      SELECT 1
                      FROM public.agents a
                      JOIN public.agent_installs i ON i.agent_id = a.id
                      WHERE a.plugin_id = p.plugin_id
                        AND a.status = 'live'
                        AND i.user_id = p_user_id
                        AND i.state <> 'removed'
                  )
              )
          )
    );
$$;

