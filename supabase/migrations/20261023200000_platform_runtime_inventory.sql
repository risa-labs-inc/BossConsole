-- Observed runtime inventory, separate from desired tiers, agent installs, and user requests.
-- Only the existing provisioner service purpose can sync; platform admins can read.
CREATE TABLE public.platform_inventory_sources (
 deployment text PRIMARY KEY CHECK (deployment ~ '^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}$'),
 last_snapshot_id uuid NOT NULL,
 last_attempt_at timestamptz NOT NULL,
 observed_at timestamptz,
 synced_at timestamptz,
 runtime_available boolean NOT NULL,
 optimist_configured boolean NOT NULL,
 max_configured boolean NOT NULL
);
CREATE TABLE public.platform_pod_inventory (
 deployment text NOT NULL REFERENCES public.platform_inventory_sources(deployment) ON DELETE CASCADE,
 instance_id text NOT NULL CHECK (instance_id ~ '^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}$'),
 state text NOT NULL CHECK (state IN ('creating','active','deleting')),
 capacity integer NOT NULL CHECK (capacity BETWEEN 1 AND 1000),
 image text CHECK (char_length(image)<=1024),
 tier text NOT NULL CHECK (tier ~ '^[a-z][a-z0-9_-]{0,31}$'),
 runtime_state text NOT NULL CHECK (runtime_state IN ('running','stopped','missing')),
 health text NOT NULL CHECK (health IN ('healthy','unhealthy','starting','none')),
 started_at bigint NOT NULL CHECK (started_at>=0),
 restart_count integer CHECK (restart_count>=0),
 PRIMARY KEY (deployment,instance_id)
);
CREATE TABLE public.platform_pod_owners (
 deployment text NOT NULL,
 instance_id text NOT NULL,
 user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
 state text NOT NULL CHECK (state IN ('assigned','disabled')),
 PRIMARY KEY (deployment,user_id),
 FOREIGN KEY (deployment,instance_id) REFERENCES public.platform_pod_inventory(deployment,instance_id) ON DELETE CASCADE
);
ALTER TABLE public.platform_inventory_sources ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.platform_pod_inventory ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.platform_pod_owners ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.platform_inventory_sources,public.platform_pod_inventory,public.platform_pod_owners FROM PUBLIC,anon,authenticated,service_role;
GRANT SELECT ON public.platform_inventory_sources,public.platform_pod_inventory,public.platform_pod_owners TO authenticated;
CREATE POLICY "platform inventory sources admin read" ON public.platform_inventory_sources FOR SELECT TO authenticated
 USING ((SELECT public.is_user_admin((SELECT auth.uid()))));
CREATE POLICY "platform inventory pods admin read" ON public.platform_pod_inventory FOR SELECT TO authenticated
 USING ((SELECT public.is_user_admin((SELECT auth.uid()))));
CREATE POLICY "platform inventory owners admin read" ON public.platform_pod_owners FOR SELECT TO authenticated
 USING ((SELECT public.is_user_admin((SELECT auth.uid()))));

CREATE FUNCTION public.sync_platform_inventory(p_deployment text,p_snapshot_id uuid,p_observed_at timestamptz,p_inventory jsonb)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE
 prev public.platform_inventory_sources;
 item jsonb; person jsonb; rt jsonb; uid uuid; email text;
 total integer;
BEGIN
 IF NOT public.is_agents_provisioner() THEN RAISE EXCEPTION 'Permission denied' USING ERRCODE='42501'; END IF;
 IF p_deployment IS NULL OR p_deployment !~ '^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}$'
    OR p_snapshot_id IS NULL OR p_observed_at IS NULL OR NOT pg_catalog.isfinite(p_observed_at)
    OR p_observed_at>clock_timestamp()+interval '5 minutes' THEN
  RAISE EXCEPTION 'invalid_inventory_snapshot' USING ERRCODE='22023';
 END IF;
 IF p_inventory IS NULL OR pg_catalog.jsonb_typeof(p_inventory)<>'object'
    OR pg_catalog.pg_column_size(p_inventory)>4194304
    OR pg_catalog.jsonb_typeof(p_inventory->'runtime_available') IS DISTINCT FROM 'boolean'
    OR pg_catalog.jsonb_typeof(p_inventory->'instances') IS DISTINCT FROM 'array'
    OR pg_catalog.jsonb_typeof(p_inventory#>'{builtins,optimist}') IS DISTINCT FROM 'boolean'
    OR pg_catalog.jsonb_typeof(p_inventory#>'{builtins,max}') IS DISTINCT FROM 'boolean' THEN
  RAISE EXCEPTION 'invalid_inventory_shape' USING ERRCODE='22023';
 END IF;
 total:=pg_catalog.jsonb_array_length(p_inventory->'instances');
 IF total>10000 THEN RAISE EXCEPTION 'inventory_too_large' USING ERRCODE='22023'; END IF;
 -- Serialize per deployment, including first insert. A slow older request cannot replace a newer snapshot.
 PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('platform_inventory:'||p_deployment,0));
 SELECT * INTO prev FROM public.platform_inventory_sources WHERE deployment=p_deployment;
 IF FOUND THEN
  IF prev.last_snapshot_id=p_snapshot_id THEN
   RETURN jsonb_build_object('accepted',false,'reason','duplicate','instances',(SELECT count(*) FROM public.platform_pod_inventory WHERE deployment=p_deployment));
  END IF;
  IF p_observed_at<=prev.last_attempt_at THEN
   RETURN jsonb_build_object('accepted',false,'reason','stale','instances',(SELECT count(*) FROM public.platform_pod_inventory WHERE deployment=p_deployment));
  END IF;
 END IF;
 INSERT INTO public.platform_inventory_sources(deployment,last_snapshot_id,last_attempt_at,runtime_available,optimist_configured,max_configured)
 VALUES(p_deployment,p_snapshot_id,p_observed_at,(p_inventory->>'runtime_available')::boolean,
        (p_inventory#>>'{builtins,optimist}')::boolean,(p_inventory#>>'{builtins,max}')::boolean)
 ON CONFLICT(deployment) DO UPDATE SET last_snapshot_id=EXCLUDED.last_snapshot_id,last_attempt_at=EXCLUDED.last_attempt_at,
 runtime_available=EXCLUDED.runtime_available;
 IF NOT (p_inventory->>'runtime_available')::boolean THEN
  -- Failure is observable, but cannot erase ownership or turn stale observations into healthy current state.
  RETURN jsonb_build_object('accepted',false,'reason','runtime_unavailable','instances',(SELECT count(*) FROM public.platform_pod_inventory WHERE deployment=p_deployment));
 END IF;
 DELETE FROM public.platform_pod_inventory WHERE deployment=p_deployment;
 FOR item IN SELECT value FROM pg_catalog.jsonb_array_elements(p_inventory->'instances') LOOP
  rt:=item->'runtime';
  IF pg_catalog.jsonb_typeof(item)<>'object'
     OR pg_catalog.jsonb_typeof(item->'users') IS DISTINCT FROM 'array'
     OR pg_catalog.jsonb_typeof(rt) IS DISTINCT FROM 'object'
     OR EXISTS(SELECT 1 FROM pg_catalog.jsonb_object_keys(item) k WHERE k NOT IN ('instance_id','state','capacity','image','tier','users','runtime'))
     OR EXISTS(SELECT 1 FROM pg_catalog.jsonb_object_keys(rt) k WHERE k NOT IN ('state','health','started_at','restart_count'))
     OR pg_catalog.jsonb_typeof(item->'capacity') IS DISTINCT FROM 'number'
     OR pg_catalog.jsonb_typeof(rt->'started_at') IS DISTINCT FROM 'number'
     OR pg_catalog.jsonb_array_length(item->'users')>(item->>'capacity')::integer THEN
   RAISE EXCEPTION 'invalid_inventory_instance' USING ERRCODE='22023';
  END IF;
  INSERT INTO public.platform_pod_inventory(deployment,instance_id,state,capacity,image,tier,runtime_state,health,started_at,restart_count)
  VALUES(p_deployment,item->>'instance_id',item->>'state',(item->>'capacity')::integer,item->>'image',item->>'tier',
         rt->>'state',rt->>'health',(rt->>'started_at')::bigint,(rt->>'restart_count')::integer);
  FOR person IN SELECT value FROM pg_catalog.jsonb_array_elements(item->'users') LOOP
   IF pg_catalog.jsonb_typeof(person)<>'object'
      OR EXISTS(SELECT 1 FROM pg_catalog.jsonb_object_keys(person) k WHERE k NOT IN ('email','boss_user_id','state')) THEN
    RAISE EXCEPTION 'invalid_inventory_owner' USING ERRCODE='22023';
   END IF;
   uid:=(person->>'boss_user_id')::uuid;
   SELECT u.email::text INTO email FROM auth.users u WHERE u.id=uid AND u.deleted_at IS NULL;
   IF NOT FOUND OR email IS NULL OR pg_catalog.lower(email) IS DISTINCT FROM pg_catalog.lower(person->>'email') THEN
    RAISE EXCEPTION 'inventory_owner_mismatch' USING ERRCODE='22023';
   END IF;
   INSERT INTO public.platform_pod_owners(deployment,instance_id,user_id,state)
   VALUES(p_deployment,item->>'instance_id',uid,person->>'state');
  END LOOP;
 END LOOP;
 UPDATE public.platform_inventory_sources SET observed_at=p_observed_at,synced_at=clock_timestamp(),
   optimist_configured=(p_inventory#>>'{builtins,optimist}')::boolean,max_configured=(p_inventory#>>'{builtins,max}')::boolean
 WHERE deployment=p_deployment;
 RETURN jsonb_build_object('accepted',true,'instances',total);
END;
$$;
REVOKE ALL ON FUNCTION public.sync_platform_inventory(text,uuid,timestamptz,jsonb) FROM PUBLIC,anon,service_role;
GRANT EXECUTE ON FUNCTION public.sync_platform_inventory(text,uuid,timestamptz,jsonb) TO authenticated;

CREATE FUNCTION public.platform_inventory() RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE result jsonb;
BEGIN
 PERFORM public.require_platform_admin();
 WITH sources AS (
  SELECT s.*,s.synced_at IS NULL OR NOT s.runtime_available OR s.observed_at<statement_timestamp()-interval '180 seconds' AS stale
  FROM public.platform_inventory_sources s
 ), pods AS (
  SELECT p.deployment,p.instance_id,p.state,p.capacity,p.image,p.tier,s.stale,
    jsonb_build_object('state',p.runtime_state,'health',p.health,'started_at',p.started_at,'restart_count',p.restart_count) AS runtime,
    COALESCE((SELECT jsonb_agg(jsonb_build_object('email',u.email,'boss_user_id',o.user_id,'state',o.state) ORDER BY u.email)
      FROM public.platform_pod_owners o JOIN auth.users u ON u.id=o.user_id
      WHERE o.deployment=p.deployment AND o.instance_id=p.instance_id),'[]'::jsonb) AS users
  FROM public.platform_pod_inventory p JOIN sources s ON s.deployment=p.deployment
 ) SELECT jsonb_build_object(
  'instances',COALESCE((SELECT jsonb_agg(to_jsonb(p) ORDER BY deployment,instance_id) FROM pods p),'[]'::jsonb),
  'builtins',jsonb_build_object('optimist',COALESCE((SELECT bool_or(optimist_configured) FROM sources),false),
                             'max',COALESCE((SELECT bool_or(max_configured) FROM sources),false)),
  'runtime_available',COALESCE((SELECT bool_and(runtime_available) FROM sources),false),
  'synced_at',(SELECT max(synced_at) FROM sources),
  'stale',COALESCE((SELECT bool_or(stale) FROM sources),true),
  'deployments',COALESCE((SELECT jsonb_agg(jsonb_build_object('deployment',deployment,'observed_at',observed_at,
     'synced_at',synced_at,'last_attempt_at',last_attempt_at,'runtime_available',runtime_available,'stale',stale) ORDER BY deployment) FROM sources),'[]'::jsonb)
 ) INTO result;
 RETURN result;
END;
$$;
REVOKE ALL ON FUNCTION public.platform_inventory() FROM PUBLIC,anon,service_role;
GRANT EXECUTE ON FUNCTION public.platform_inventory() TO authenticated;
