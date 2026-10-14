-- Opaque host-owned delegation into the existing application-sharing Edge function.
CREATE TABLE public.fluck_web_app_sharing_grants (
 grant_hash text PRIMARY KEY CHECK (grant_hash ~ '^[0-9a-f]{64}$'),
 user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
 instance_id text NOT NULL, attempt_id uuid NOT NULL, session_id uuid NOT NULL, generation uuid NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), expires_at timestamptz NOT NULL, revoked_at timestamptz,
 FOREIGN KEY (user_id,instance_id) REFERENCES public.fluck_web_instances(user_id,instance_id) ON DELETE CASCADE
);
CREATE INDEX fluck_web_app_sharing_grants_owner_created ON public.fluck_web_app_sharing_grants(user_id,created_at DESC);
CREATE INDEX fluck_web_app_sharing_grants_scope ON public.fluck_web_app_sharing_grants(user_id,instance_id,attempt_id);
ALTER TABLE public.fluck_web_app_sharing_grants ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.fluck_web_app_sharing_grants FROM PUBLIC,anon,authenticated;
GRANT ALL ON public.fluck_web_app_sharing_grants TO service_role;

CREATE FUNCTION public.fluck_web_request_app_sharing(p_instance_id text,p_attempt_id uuid,p_session_id uuid,p_generation uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=auth.uid(); token text; expiry timestamptz:=now()+interval '10 minutes'; instance_uuid uuid;
BEGIN
 IF actor IS NULL THEN RAISE EXCEPTION 'Not signed in' USING ERRCODE='42501'; END IF;
 IF p_instance_id IS NULL OR p_attempt_id IS NULL OR p_session_id IS NULL OR p_generation IS NULL THEN RAISE EXCEPTION 'invalid_request' USING ERRCODE='22023'; END IF;
 BEGIN instance_uuid:=p_instance_id::uuid; EXCEPTION WHEN invalid_text_representation THEN RAISE EXCEPTION 'invalid_request' USING ERRCODE='22023'; END;
 IF NOT EXISTS(SELECT 1 FROM public.fluck_web_instances i WHERE i.user_id=actor AND i.instance_id=p_instance_id AND i.last_seen_at>now()-interval '90 seconds') THEN RAISE EXCEPTION 'instance_unavailable' USING ERRCODE='P0001'; END IF;
 IF EXISTS(SELECT 1 FROM public.app_share_sessions s WHERE s.id=p_session_id) AND NOT EXISTS(
  SELECT 1 FROM public.app_share_sessions s WHERE s.id=p_session_id AND s.owner_id=actor AND s.generation=p_generation AND s.instance_id=instance_uuid AND s.expires_at>now()
 ) THEN RAISE EXCEPTION 'session_unavailable' USING ERRCODE='P0001'; END IF;
 PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(actor::text,27));
 DELETE FROM public.fluck_web_app_sharing_grants g WHERE g.user_id=actor AND g.created_at<=now()-interval '1 hour';
 IF (SELECT count(*) FROM public.fluck_web_app_sharing_grants g WHERE g.user_id=actor AND g.created_at>now()-interval '1 minute')>=20 THEN RAISE EXCEPTION 'rate_limited' USING ERRCODE='P0001'; END IF;
 UPDATE public.fluck_web_app_sharing_grants g SET revoked_at=now() WHERE g.user_id=actor AND g.instance_id=p_instance_id AND g.attempt_id=p_attempt_id AND g.revoked_at IS NULL;
 token:=pg_catalog.translate(pg_catalog.encode(extensions.gen_random_bytes(32),'base64'),'+/=','-_');
 INSERT INTO public.fluck_web_app_sharing_grants(grant_hash,user_id,instance_id,attempt_id,session_id,generation,expires_at)
 VALUES(pg_catalog.encode(extensions.digest(token,'sha256'),'hex'),actor,p_instance_id,p_attempt_id,p_session_id,p_generation,expiry);
 RETURN jsonb_build_object('grant',token,'expires_at',expiry);
END $$;

CREATE FUNCTION public.fluck_web_revoke_app_sharing(p_instance_id text,p_attempt_id uuid) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=auth.uid(); changed integer;
BEGIN
 IF actor IS NULL OR p_instance_id IS NULL OR p_attempt_id IS NULL THEN RAISE EXCEPTION 'Not signed in' USING ERRCODE='42501'; END IF;
 UPDATE public.fluck_web_app_sharing_grants g SET revoked_at=now() WHERE g.user_id=actor AND g.instance_id=p_instance_id AND g.attempt_id=p_attempt_id AND g.revoked_at IS NULL;
 GET DIAGNOSTICS changed=ROW_COUNT; RETURN changed>0;
END $$;

CREATE FUNCTION public.fluck_web_authorize_app_sharing(p_grant text,p_session_id uuid,p_generation uuid,p_action text,p_instance_id text DEFAULT NULL) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE g public.fluck_web_app_sharing_grants;
BEGIN
 IF p_grant IS NULL OR p_grant!~'^[A-Za-z0-9_-]{43}$' OR p_session_id IS NULL OR p_generation IS NULL OR p_action IS NULL OR p_action NOT IN(
  'register','heartbeat','stop','admit','consume','peerHeartbeat','revoke','mediaDemand','mediaCreate','mediaPublish','mediaSubscribe','mediaRenegotiate','mediaClose','dataEstablish','dataPublish','dataSubscribe','dataRevoke','controlAcquire','controlRenew','controlRelease','controlPoll'
 ) THEN RAISE EXCEPTION 'grant_invalid' USING ERRCODE='P0001'; END IF;
 SELECT x.* INTO g FROM public.fluck_web_app_sharing_grants x JOIN public.fluck_web_instances i ON i.user_id=x.user_id AND i.instance_id=x.instance_id
 WHERE x.grant_hash=pg_catalog.encode(extensions.digest(p_grant,'sha256'),'hex') AND x.revoked_at IS NULL AND x.expires_at>now()
 AND x.session_id=p_session_id AND x.generation=p_generation AND i.last_seen_at>now()-interval '90 seconds';
 IF g.user_id IS NULL THEN RAISE EXCEPTION 'grant_invalid' USING ERRCODE='P0001'; END IF;
 IF p_action='register' THEN
  IF p_instance_id IS DISTINCT FROM g.instance_id OR EXISTS(SELECT 1 FROM public.app_share_sessions s WHERE s.id=p_session_id) THEN RAISE EXCEPTION 'grant_invalid' USING ERRCODE='P0001'; END IF;
  BEGIN PERFORM p_instance_id::uuid; EXCEPTION WHEN invalid_text_representation THEN RAISE EXCEPTION 'grant_invalid' USING ERRCODE='P0001'; END;
 ELSIF NOT EXISTS(SELECT 1 FROM public.app_share_sessions s WHERE s.id=p_session_id AND s.owner_id=g.user_id AND s.generation=p_generation AND s.instance_id=g.instance_id::uuid AND s.expires_at>now()) THEN
  RAISE EXCEPTION 'grant_invalid' USING ERRCODE='P0001';
 END IF;
 RETURN g.user_id;
END $$;

REVOKE ALL ON FUNCTION public.fluck_web_request_app_sharing(text,uuid,uuid,uuid) FROM PUBLIC,anon,service_role;
GRANT EXECUTE ON FUNCTION public.fluck_web_request_app_sharing(text,uuid,uuid,uuid) TO authenticated;
REVOKE ALL ON FUNCTION public.fluck_web_revoke_app_sharing(text,uuid) FROM PUBLIC,anon,service_role;
GRANT EXECUTE ON FUNCTION public.fluck_web_revoke_app_sharing(text,uuid) TO authenticated;
REVOKE ALL ON FUNCTION public.fluck_web_authorize_app_sharing(text,uuid,uuid,text,text) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.fluck_web_authorize_app_sharing(text,uuid,uuid,text,text) TO service_role;
