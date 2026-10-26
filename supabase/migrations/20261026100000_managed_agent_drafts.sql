-- Editable source content is shared by managers, separate from signed/published artifacts.
CREATE TABLE public.agent_content_drafts (
 agent_id uuid PRIMARY KEY REFERENCES public.agents(id) ON DELETE CASCADE,
 revision bigint NOT NULL CHECK(revision>0),
 content jsonb NOT NULL,
 updated_by uuid REFERENCES auth.users(id) ON DELETE SET NULL,
 updated_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE public.agent_content_drafts ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.agent_content_drafts FROM PUBLIC,anon,authenticated,service_role;

CREATE FUNCTION public.validate_agent_draft_content(p jsonb) RETURNS void
LANGUAGE plpgsql IMMUTABLE SET search_path='' AS $$
DECLARE c jsonb; k text; v jsonb; host text; names text[]:='{}'; ids text[]:='{}'; normalized text;
BEGIN
 IF jsonb_typeof(p) IS DISTINCT FROM 'object' OR octet_length(p::text)>1048576
 OR NOT(p ?& ARRAY['soul','identity','skills','custom_connectors'])
 OR EXISTS(SELECT 1 FROM jsonb_object_keys(p)x WHERE x NOT IN('soul','identity','skills','custom_connectors')) THEN
  RAISE EXCEPTION 'invalid_draft_content' USING ERRCODE='22023';
 END IF;
 IF jsonb_typeof(p->'soul') IS DISTINCT FROM 'string' OR octet_length(p->>'soul')>262144
 OR jsonb_typeof(p->'identity') IS DISTINCT FROM 'string' OR octet_length(p->>'identity')>262144
 OR jsonb_typeof(p->'skills') IS DISTINCT FROM 'object' THEN RAISE EXCEPTION 'invalid_draft_content' USING ERRCODE='22023'; END IF;
 IF (SELECT count(*) FROM jsonb_object_keys(p->'skills'))>100 THEN RAISE EXCEPTION 'too_many_skills' USING ERRCODE='22023'; END IF;
 IF EXISTS(SELECT 1 FROM jsonb_object_keys(p->'skills') AS skill_keys(skill_name) GROUP BY lower(skill_keys.skill_name) HAVING count(*)>1) THEN RAISE EXCEPTION 'duplicate_skill' USING ERRCODE='22023'; END IF;
 FOR k,v IN SELECT * FROM jsonb_each(p->'skills') LOOP
  IF k !~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$' OR jsonb_typeof(v) IS DISTINCT FROM 'string' OR octet_length(v#>>'{}')>262144 THEN
   RAISE EXCEPTION 'invalid_skill' USING ERRCODE='22023';
  END IF;
 END LOOP;
 IF jsonb_typeof(p->'custom_connectors') IS DISTINCT FROM 'array' THEN RAISE EXCEPTION 'invalid_connectors' USING ERRCODE='22023'; END IF;
 IF jsonb_array_length(p->'custom_connectors')>20 OR octet_length((p->'custom_connectors')::text)>65536 THEN RAISE EXCEPTION 'too_many_connectors' USING ERRCODE='22023'; END IF;
 FOR c IN SELECT * FROM jsonb_array_elements(p->'custom_connectors') LOOP
  IF jsonb_typeof(c) IS DISTINCT FROM 'object' OR NOT(c ?& ARRAY['id','name','url','auth']) THEN RAISE EXCEPTION 'invalid_connector' USING ERRCODE='22023'; END IF;
  IF EXISTS(SELECT 1 FROM jsonb_object_keys(c)x WHERE x NOT IN('id','name','url','auth'))
  OR EXISTS(SELECT 1 FROM jsonb_each(c)e WHERE jsonb_typeof(e.value)<>'string')
  OR c->>'id' !~ '^mcp-[a-z0-9][a-z0-9-]{0,47}$' OR c->>'name' !~ '^[A-Za-z0-9][A-Za-z0-9 .-]{0,39}$'
  OR c->>'auth' NOT IN('none','oauth') OR char_length(c->>'url')>2048
  OR c->>'url' !~ '^https://[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?(:443)?(/[^?#[:space:]]*)?$'
  OR c->>'url' ~ '[\\?#@]' THEN RAISE EXCEPTION 'invalid_connector' USING ERRCODE='22023'; END IF;
  host:=lower(split_part(split_part(substring(c->>'url' FROM 9),'/',1),':',1));
  IF host !~ '^([a-z0-9]([a-z0-9-]*[a-z0-9])?\.)+[a-z][a-z0-9-]*[a-z0-9]$'
  OR host ~ '(^|\.)(localhost|local|internal|lan|home|test|invalid|onion)$' THEN RAISE EXCEPTION 'connector_requires_public_https' USING ERRCODE='22023'; END IF;
  normalized:=trim(both '_' FROM regexp_replace(lower(c->>'name'),'[^a-z0-9_]+','_','g'));
  IF normalized=ANY(ARRAY['boss','atlas','aigateway','google','google_workspace','gmail','google_calendar','github','notion','linear','todoist','granola','slack']) THEN RAISE EXCEPTION 'reserved_connector_name' USING ERRCODE='22023'; END IF;
  IF c->>'id'=ANY(ids) OR normalized=ANY(names) THEN RAISE EXCEPTION 'duplicate_connector' USING ERRCODE='22023'; END IF;
  ids:=array_append(ids,c->>'id');names:=array_append(names,normalized);
 END LOOP;
END;
$$;
REVOKE ALL ON FUNCTION public.validate_agent_draft_content(jsonb) FROM PUBLIC,anon,authenticated,service_role;

CREATE FUNCTION public.managed_agents_list() RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
 IF auth.uid() IS NULL THEN RAISE EXCEPTION 'Permission denied' USING ERRCODE='42501'; END IF;
 RETURN COALESCE((SELECT jsonb_agg(jsonb_build_object('id',a.id,'org_id',a.org_id,'name',a.name,'slug',a.slug,'plugin_id',a.plugin_id,'live_version',a.live_version,'pending_version',a.pending_version) ORDER BY a.id)
 FROM public.agents a WHERE public.has_agent_permission(a.id,'agents.edit')),'[]'::jsonb);
END;
$$;
CREATE FUNCTION public.managed_agent_draft_get(p_agent_id uuid) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE a public.agents%ROWTYPE; d public.agent_content_drafts%ROWTYPE;
BEGIN
 IF auth.uid() IS NULL OR NOT public.has_agent_permission(p_agent_id,'agents.edit') THEN RAISE EXCEPTION 'Permission denied' USING ERRCODE='42501'; END IF;
 SELECT * INTO STRICT a FROM public.agents WHERE id=p_agent_id;
 SELECT * INTO d FROM public.agent_content_drafts WHERE agent_id=p_agent_id;
 RETURN jsonb_build_object('agent_id',a.id,'plugin_id',a.plugin_id,'live_version',a.live_version,'pending_version',a.pending_version,'revision',COALESCE(d.revision,0),
 'content',COALESCE(d.content,'{"soul":"","identity":"","skills":{},"custom_connectors":[]}'::jsonb),'updated_at',d.updated_at);
END;
$$;
CREATE FUNCTION public.managed_agent_draft_update(p_agent_id uuid,p_expected_revision bigint,p_patch jsonb) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE a public.agents%ROWTYPE; d public.agent_content_drafts%ROWTYPE; content jsonb; old_revision bigint;
BEGIN
 -- Lock the agent as the stable serialization point, including the first draft insert.
 SELECT * INTO a FROM public.agents WHERE id=p_agent_id FOR UPDATE;
 IF NOT FOUND OR auth.uid() IS NULL OR NOT public.has_agent_permission(p_agent_id,'agents.edit') THEN RAISE EXCEPTION 'Permission denied' USING ERRCODE='42501'; END IF;
 IF jsonb_typeof(p_patch) IS DISTINCT FROM 'object' OR p_patch='{}'::jsonb OR EXISTS(SELECT 1 FROM jsonb_object_keys(p_patch)x WHERE x NOT IN('soul','identity','skills','custom_connectors')) THEN RAISE EXCEPTION 'invalid_draft_patch' USING ERRCODE='22023'; END IF;
 SELECT * INTO d FROM public.agent_content_drafts WHERE agent_id=p_agent_id;
 old_revision:=COALESCE(d.revision,0);
 IF p_expected_revision IS NULL OR p_expected_revision<>old_revision THEN RAISE EXCEPTION 'draft_revision_conflict' USING ERRCODE='40001'; END IF;
 IF old_revision=0 AND (a.live_version IS NOT NULL OR a.pending_version IS NOT NULL) AND NOT(p_patch ?& ARRAY['soul','identity','skills','custom_connectors']) THEN
  RAISE EXCEPTION 'published_source_required' USING ERRCODE='22023';
 END IF;
 content:=COALESCE(d.content,'{"soul":"","identity":"","skills":{},"custom_connectors":[]}'::jsonb)||p_patch;
 PERFORM public.validate_agent_draft_content(content);
 INSERT INTO public.agent_content_drafts(agent_id,revision,content,updated_by,updated_at)
 VALUES(p_agent_id,old_revision+1,content,auth.uid(),clock_timestamp())
 ON CONFLICT(agent_id) DO UPDATE SET revision=EXCLUDED.revision,content=EXCLUDED.content,updated_by=EXCLUDED.updated_by,updated_at=EXCLUDED.updated_at;
 INSERT INTO public.agent_audit_log(org_id,agent_id,actor,action,detail)
 VALUES(a.org_id,a.id,auth.uid(),'agent.draft_updated',jsonb_build_object('revision',old_revision+1,'fields',(SELECT jsonb_agg(x ORDER BY x) FROM jsonb_object_keys(p_patch)x)));
 RETURN public.managed_agent_draft_get(p_agent_id);
END;
$$;
REVOKE ALL ON FUNCTION public.managed_agents_list(),public.managed_agent_draft_get(uuid),public.managed_agent_draft_update(uuid,bigint,jsonb) FROM PUBLIC,anon,service_role;
GRANT EXECUTE ON FUNCTION public.managed_agents_list(),public.managed_agent_draft_get(uuid),public.managed_agent_draft_update(uuid,bigint,jsonb) TO authenticated;
