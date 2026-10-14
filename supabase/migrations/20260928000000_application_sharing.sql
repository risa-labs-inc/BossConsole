-- Additive, owner-scoped application sharing. Terminal sessions/tickets are untouched.
CREATE TABLE public.user_app_sharing_preferences (
 user_id uuid PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
 auto_admit boolean NOT NULL DEFAULT true, auto_control boolean NOT NULL DEFAULT true,
 revision bigint NOT NULL DEFAULT 1 CHECK(revision > 0)
);
CREATE TABLE public.app_share_sessions (
 id uuid PRIMARY KEY, owner_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
 generation uuid NOT NULL, device_id uuid NOT NULL, instance_id uuid NOT NULL,
 name text NOT NULL, windows jsonb NOT NULL, viewer_url text NOT NULL,
 key_epoch uuid NOT NULL, host_public_key text NOT NULL,
 host_peer_id uuid NOT NULL DEFAULT gen_random_uuid(), expires_at timestamptz NOT NULL,
 UNIQUE(id,generation)
);
CREATE INDEX app_share_sessions_owner_expiry ON public.app_share_sessions(owner_id,expires_at);
CREATE TABLE public.app_share_tickets (
 digest bytea PRIMARY KEY, session_id uuid NOT NULL REFERENCES public.app_share_sessions(id) ON DELETE CASCADE,
 generation uuid NOT NULL, actor_id uuid NOT NULL, device_id uuid NOT NULL,
 role text NOT NULL CHECK(role IN ('view','control')), audience text NOT NULL DEFAULT 'boss-app-share/1',
 expires_at timestamptz NOT NULL
);
CREATE INDEX app_share_tickets_session_expiry ON public.app_share_tickets(session_id,expires_at);
CREATE TABLE public.app_share_peers (
 id uuid PRIMARY KEY, session_id uuid NOT NULL REFERENCES public.app_share_sessions(id) ON DELETE CASCADE,
 generation uuid NOT NULL, actor_id uuid NOT NULL, device_id uuid NOT NULL,
 role text NOT NULL CHECK(role IN ('view','control')), expires_at timestamptz NOT NULL
);
CREATE INDEX app_share_peers_session ON public.app_share_peers(session_id);
CREATE TABLE public.app_share_media (
 session_id uuid NOT NULL REFERENCES public.app_share_sessions(id) ON DELETE CASCADE,
 peer_id uuid NOT NULL, window_id text NOT NULL, generation uuid NOT NULL,
 sfu_id text NOT NULL, publisher boolean NOT NULL, mid text, track_name text, channel_id integer, transport_channel_id integer,
 PRIMARY KEY(session_id,peer_id,window_id)
);
CREATE TABLE public.app_share_control (
 session_id uuid PRIMARY KEY REFERENCES public.app_share_sessions(id) ON DELETE CASCADE,
 generation uuid NOT NULL, peer_id uuid NOT NULL, lease_id uuid NOT NULL DEFAULT gen_random_uuid(),
 secret text NOT NULL, sequence bigint NOT NULL DEFAULT 0, expires_at timestamptz NOT NULL
);
CREATE TABLE public.app_share_media_operations (
 session_id uuid NOT NULL REFERENCES public.app_share_sessions(id) ON DELETE CASCADE,
 peer_id uuid NOT NULL, window_id text NOT NULL, operation uuid NOT NULL, expires_at timestamptz NOT NULL,
 PRIMARY KEY(session_id,peer_id,window_id)
);
CREATE TABLE public.app_share_media_cleanup (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, owner_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
 descriptor jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX app_share_media_cleanup_owner ON public.app_share_media_cleanup(owner_id,id);
CREATE FUNCTION public.app_share_queue_media_cleanup() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
BEGIN
 IF OLD.mid IS NOT NULL OR OLD.channel_id IS NOT NULL OR OLD.transport_channel_id IS NOT NULL THEN
 INSERT INTO public.app_share_media_cleanup(owner_id,descriptor)
 SELECT owner_id,to_jsonb(OLD) FROM public.app_share_sessions WHERE id=OLD.session_id;
 END IF;
 RETURN OLD;
END $$;
REVOKE ALL ON FUNCTION public.app_share_queue_media_cleanup() FROM PUBLIC,anon,authenticated;
CREATE TRIGGER app_share_cleanup_before_delete BEFORE DELETE ON public.app_share_media FOR EACH ROW EXECUTE FUNCTION public.app_share_queue_media_cleanup();
DO $$ DECLARE t text; BEGIN
 FOREACH t IN ARRAY ARRAY['user_app_sharing_preferences','app_share_sessions','app_share_tickets','app_share_peers','app_share_media','app_share_control','app_share_media_operations','app_share_media_cleanup'] LOOP
 EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY',t);
 EXECUTE format('REVOKE ALL ON public.%I FROM PUBLIC, anon, authenticated',t);
 END LOOP;
END $$;
CREATE FUNCTION public.app_sharing_actor(p_actor_id uuid DEFAULT NULL) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
BEGIN
 IF auth.role()='service_role' AND p_actor_id IS NOT NULL THEN RETURN p_actor_id; END IF;
 IF p_actor_id IS NOT NULL OR auth.uid() IS NULL THEN RAISE EXCEPTION 'Unauthorized' USING ERRCODE='42501'; END IF;
 RETURN auth.uid();
END $$;
REVOKE ALL ON FUNCTION public.app_sharing_actor(uuid) FROM PUBLIC, anon, authenticated;
CREATE FUNCTION public.get_user_app_sharing_preferences(p_actor_id uuid DEFAULT NULL) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=public.app_sharing_actor(p_actor_id); result jsonb;
BEGIN
 SELECT jsonb_build_object('auto_admit',auto_admit,'auto_control',auto_control,'revision',revision) INTO result FROM public.user_app_sharing_preferences WHERE user_id=actor;
 RETURN coalesce(result,'{"auto_admit":true,"auto_control":true,"revision":0}'::jsonb);
END $$;
-- Retiring a generation prevents continued publication while provider teardown retries.
CREATE FUNCTION public.retire_app_sharing_generation(p_session uuid) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
BEGIN
 DELETE FROM public.app_share_media WHERE session_id=p_session;
 DELETE FROM public.app_share_peers WHERE session_id=p_session;
 DELETE FROM public.app_share_control WHERE session_id=p_session;
 DELETE FROM public.app_share_tickets WHERE session_id=p_session;
 DELETE FROM public.app_share_media_operations WHERE session_id=p_session;
 UPDATE public.app_share_sessions SET expires_at=least(expires_at,now()) WHERE id=p_session;
END $$;
REVOKE ALL ON FUNCTION public.retire_app_sharing_generation(uuid) FROM PUBLIC,anon,authenticated;
CREATE FUNCTION public.set_user_app_sharing_preferences(p_auto_admit boolean,p_auto_control boolean,p_revision bigint,p_actor_id uuid DEFAULT NULL) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=public.app_sharing_actor(p_actor_id); changed uuid; owned record;
BEGIN
 IF p_auto_admit IS NULL OR p_auto_control IS NULL OR p_revision IS NULL OR p_revision<0 THEN RAISE EXCEPTION 'Invalid preferences' USING ERRCODE='22023'; END IF;
 PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(actor::text,928));
 IF p_revision=0 THEN INSERT INTO public.user_app_sharing_preferences(user_id,auto_admit,auto_control) VALUES(actor,p_auto_admit,p_auto_control) ON CONFLICT DO NOTHING RETURNING user_id INTO changed;
 ELSE UPDATE public.user_app_sharing_preferences SET auto_admit=p_auto_admit,auto_control=p_auto_control,revision=revision+1 WHERE user_id=actor AND revision=p_revision RETURNING user_id INTO changed; END IF;
 IF changed IS NULL THEN RAISE EXCEPTION 'Preferences changed' USING ERRCODE='PT409'; END IF;
 IF NOT p_auto_admit THEN
  FOR owned IN SELECT id FROM public.app_share_sessions WHERE owner_id=actor ORDER BY id FOR UPDATE LOOP
   PERFORM public.retire_app_sharing_generation(owned.id);
  END LOOP;
 ELSIF NOT p_auto_control THEN
  DELETE FROM public.app_share_control WHERE session_id IN (SELECT id FROM public.app_share_sessions WHERE owner_id=actor);
 END IF;
 RETURN public.get_user_app_sharing_preferences(p_actor_id);
END $$;
CREATE FUNCTION public.app_sharing_command(p_action text,p_body jsonb DEFAULT '{}'::jsonb,p_actor_id uuid DEFAULT NULL) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=public.app_sharing_actor(p_actor_id); s public.app_share_sessions; peer public.app_share_peers;
 ticket public.app_share_tickets; lease public.app_share_control; media public.app_share_media;
 sid uuid; gen uuid; pid uuid; win text; token text; result jsonb; prefs jsonb; is_host boolean; n integer;
BEGIN
 IF p_body IS NULL OR jsonb_typeof(p_body)<>'object' OR octet_length(p_body::text)>131072 THEN RAISE EXCEPTION 'Invalid body' USING ERRCODE='22023'; END IF;
 IF p_action IN ('_cleanupList','_cleanupAck') THEN
  IF auth.role()<>'service_role' OR p_actor_id IS NULL THEN RAISE EXCEPTION 'Internal only' USING ERRCODE='42501'; END IF;
  IF p_action='_cleanupAck' THEN DELETE FROM public.app_share_media_cleanup WHERE owner_id=actor AND id=(p_body->>'cleanup_id')::bigint; RETURN '{"done":true}'; END IF;
  WITH selected AS (SELECT id FROM public.app_share_media_cleanup WHERE owner_id=actor AND next_attempt_at<=now() ORDER BY next_attempt_at,id LIMIT 8 FOR UPDATE SKIP LOCKED),
  claimed AS (UPDATE public.app_share_media_cleanup c SET attempts=least(attempts+1,1000000),next_attempt_at=now()+make_interval(secs=>least(3600,60*(1<<least(c.attempts,6)))) FROM selected WHERE c.id=selected.id RETURNING c.id,c.descriptor)
  SELECT coalesce(jsonb_agg(to_jsonb(c)),'[]'::jsonb) INTO result FROM claimed c;
  RETURN result;
 END IF;
 IF p_action='list' THEN
  SELECT coalesce(jsonb_agg((to_jsonb(x)-'owner_id'-'host_peer_id')||jsonb_build_object('session_id',x.id)),'[]'::jsonb) INTO result FROM (SELECT * FROM public.app_share_sessions WHERE owner_id=actor AND expires_at>now() ORDER BY expires_at DESC LIMIT 100) x;
  RETURN jsonb_build_object('sessions',result);
 END IF;
 sid:=(p_body->>'session_id')::uuid; gen:=(p_body->>'generation')::uuid;
 IF sid IS NULL OR gen IS NULL THEN RAISE EXCEPTION 'Missing session' USING ERRCODE='22023'; END IF;
 PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(sid::text,929));
 IF p_action='register' THEN
  PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(actor::text,930));
  IF coalesce(length(p_body->>'name'),0) NOT BETWEEN 1 AND 120 OR jsonb_typeof(p_body->'windows') IS DISTINCT FROM 'array' OR
    jsonb_array_length(p_body->'windows') NOT BETWEEN 1 AND 16 OR coalesce(length(p_body->>'viewer_url'),0)>4096 OR
    coalesce(p_body->>'viewer_url','') !~ '^https://[^[:space:]#]+#k=[A-Za-z0-9_-]{43}$' OR
    coalesce(p_body->>'host_public_key','') !~ '^[A-Za-z0-9_-]{40,255}$' OR
    p_body->>'key_epoch' IS NULL OR p_body->>'device_id' IS NULL OR p_body->>'instance_id' IS NULL THEN
    RAISE EXCEPTION 'Invalid descriptor' USING ERRCODE='22023'; END IF;
  IF EXISTS(SELECT 1 FROM jsonb_array_elements(p_body->'windows') w WHERE jsonb_typeof(w)<>'object' OR coalesce(w->>'id','') !~ '^[A-Za-z0-9_.:-]{1,128}$' OR coalesce(length(w->>'title'),0)>160) OR
    (SELECT count(DISTINCT w->>'id') FROM jsonb_array_elements(p_body->'windows') w)<>jsonb_array_length(p_body->'windows') THEN RAISE EXCEPTION 'Invalid windows' USING ERRCODE='22023'; END IF;
  SELECT * INTO s FROM public.app_share_sessions WHERE id=sid;
  IF FOUND THEN
    IF s.owner_id<>actor THEN RAISE EXCEPTION 'Forbidden' USING ERRCODE='42501'; END IF;
    -- Re-registering a generation cannot silently replace media keys/windows.
    IF s.generation=gen OR s.key_epoch=(p_body->>'key_epoch')::uuid THEN RAISE EXCEPTION 'Already registered; heartbeat or start new generation' USING ERRCODE='PT409'; END IF;
    DELETE FROM public.app_share_media WHERE session_id=sid;
    DELETE FROM public.app_share_sessions WHERE id=sid;
  END IF;
  IF (SELECT count(*) FROM public.app_share_sessions WHERE owner_id=actor AND expires_at>now())>=32 THEN RAISE EXCEPTION 'Capacity' USING ERRCODE='PT429'; END IF;
  INSERT INTO public.app_share_sessions(id,owner_id,generation,device_id,instance_id,name,windows,viewer_url,key_epoch,host_public_key,expires_at)
  VALUES(sid,actor,gen,(p_body->>'device_id')::uuid,(p_body->>'instance_id')::uuid,p_body->>'name',p_body->'windows',p_body->>'viewer_url',(p_body->>'key_epoch')::uuid,p_body->>'host_public_key',now()+interval '90 seconds') RETURNING * INTO s;
  RETURN (to_jsonb(s)-'owner_id')||jsonb_build_object('session_id',s.id);
 END IF;
 SELECT * INTO s FROM public.app_share_sessions WHERE id=sid AND generation=gen AND owner_id=actor AND expires_at>now() FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Session unavailable' USING ERRCODE='42501'; END IF;
 -- Commit retirement before Edge returns session_retired; raising would roll it back.
 IF EXISTS(SELECT 1 FROM public.app_share_peers p JOIN public.app_share_media m ON m.session_id=p.session_id AND m.peer_id=p.id WHERE p.session_id=sid AND p.expires_at<=now()) THEN
  PERFORM public.retire_app_sharing_generation(sid);
  RETURN '{"generation_revoked":true,"must_stop":true}';
 END IF;
 DELETE FROM public.app_share_control WHERE session_id=sid AND peer_id IN (SELECT id FROM public.app_share_peers WHERE session_id=sid AND expires_at<=now());
 DELETE FROM public.app_share_peers WHERE session_id=sid AND expires_at<=now();
 IF p_action='heartbeat' THEN UPDATE public.app_share_sessions SET expires_at=now()+interval '90 seconds' WHERE id=sid; RETURN jsonb_build_object('expires_at',now()+interval '90 seconds'); END IF;
 IF p_action='stop' THEN DELETE FROM public.app_share_media WHERE session_id=sid; DELETE FROM public.app_share_sessions WHERE id=sid; RETURN '{"stopped":true}'; END IF;
 prefs:=public.get_user_app_sharing_preferences(p_actor_id);
 IF p_action='admit' THEN
  IF p_body->>'role' IS NULL OR p_body->>'role' NOT IN ('view','control') OR p_body->>'device_id' IS NULL THEN RAISE EXCEPTION 'Invalid admission' USING ERRCODE='22023'; END IF;
  IF NOT (prefs->>'auto_admit')::boolean OR (p_body->>'role'='control' AND NOT (prefs->>'auto_control')::boolean) THEN RAISE EXCEPTION 'Host approval required; manual grants not enabled' USING ERRCODE='PT403'; END IF;
  DELETE FROM public.app_share_tickets WHERE session_id=sid AND expires_at<=now();
  IF (SELECT count(*) FROM public.app_share_tickets WHERE session_id=sid)>=32 THEN RAISE EXCEPTION 'Capacity' USING ERRCODE='PT429'; END IF;
  token:=translate(encode(extensions.gen_random_bytes(32),'base64'),'+/=','-_');
  INSERT INTO public.app_share_tickets VALUES(extensions.digest(token,'sha256'),sid,gen,actor,(p_body->>'device_id')::uuid,p_body->>'role','boss-app-share/1',now()+interval '60 seconds');
  RETURN jsonb_build_object('ticket',token,'expires_at',now()+interval '60 seconds','key_epoch',s.key_epoch,'host_public_key',s.host_public_key,'viewer_url',s.viewer_url);
 END IF;
 IF p_action='consume' THEN
  DELETE FROM public.app_share_tickets WHERE digest=extensions.digest(p_body->>'ticket','sha256') AND session_id=sid AND generation=gen AND actor_id=actor AND device_id=(p_body->>'device_id')::uuid AND role=p_body->>'role' AND audience='boss-app-share/1' AND expires_at>now() RETURNING * INTO ticket;
  IF NOT FOUND OR NOT (prefs->>'auto_admit')::boolean OR (ticket.role='control' AND NOT (prefs->>'auto_control')::boolean) THEN RAISE EXCEPTION 'Admission denied' USING ERRCODE='42501'; END IF;
  DELETE FROM public.app_share_peers WHERE session_id=sid AND expires_at<=now();
  IF (SELECT count(*) FROM public.app_share_peers WHERE session_id=sid)>=32 THEN RAISE EXCEPTION 'Capacity' USING ERRCODE='PT429'; END IF;
  pid:=gen_random_uuid();
  INSERT INTO public.app_share_peers VALUES(pid,sid,gen,actor,ticket.device_id,ticket.role,now()+interval '10 minutes');
  RETURN jsonb_build_object('peer_id',pid,'role',ticket.role,'expires_at',now()+interval '10 minutes');
 END IF;
 pid:=coalesce(p_body->>'peer_id',p_body->>'host_peer_id')::uuid;
 is_host:=pid=s.host_peer_id;
 IF NOT coalesce(is_host,false) THEN
  SELECT * INTO peer FROM public.app_share_peers WHERE id=pid AND session_id=sid AND generation=gen AND actor_id=actor AND expires_at>now();
  IF NOT FOUND THEN RAISE EXCEPTION 'Peer denied' USING ERRCODE='42501'; END IF;
 END IF;
 IF p_action='mediaDemand' THEN
  IF NOT is_host THEN RAISE EXCEPTION 'Host only' USING ERRCODE='42501'; END IF;
  SELECT jsonb_agg(jsonb_build_object('window_id',w->>'id','viewers',
   (SELECT count(*) FROM public.app_share_media m JOIN public.app_share_peers p ON p.id=m.peer_id AND p.session_id=m.session_id AND p.generation=m.generation
    WHERE m.session_id=sid AND m.generation=gen AND m.window_id=w->>'id' AND NOT m.publisher AND p.actor_id=actor AND p.expires_at>now())))
   INTO result FROM jsonb_array_elements(s.windows) w;
  RETURN jsonb_build_object('windows',coalesce(result,'[]'::jsonb));
 END IF;
 IF p_action='peerHeartbeat' THEN
  IF is_host OR NOT (prefs->>'auto_admit')::boolean THEN RAISE EXCEPTION 'Admission denied' USING ERRCODE='42501'; END IF;
  UPDATE public.app_share_peers SET expires_at=now()+interval '10 minutes' WHERE id=pid;
  RETURN jsonb_build_object('expires_at',now()+interval '10 minutes');
 END IF;
 IF p_action='revoke' THEN
  IF NOT is_host THEN RAISE EXCEPTION 'Host only' USING ERRCODE='42501'; END IF;
  IF NOT EXISTS(SELECT 1 FROM public.app_share_peers WHERE session_id=sid AND id=(p_body->>'target_peer_id')::uuid) THEN RAISE EXCEPTION 'Peer denied' USING ERRCODE='42501'; END IF;
  PERFORM public.retire_app_sharing_generation(sid);
  RETURN '{"revoked":true,"must_stop":true}';
 END IF;
 IF p_action='controlAcquire' THEN
  IF is_host OR peer.role<>'control' OR NOT (prefs->>'auto_control')::boolean THEN RAISE EXCEPTION 'Control denied' USING ERRCODE='42501'; END IF;
  SELECT * INTO lease FROM public.app_share_control WHERE session_id=sid AND expires_at>now();
  IF FOUND THEN
    IF lease.peer_id<>pid THEN RAISE EXCEPTION 'Controller busy' USING ERRCODE='PT409'; END IF;
    RETURN jsonb_build_object('lease_id',lease.lease_id,'peer_id',lease.peer_id,'expires_at',lease.expires_at,'control_secret',lease.secret);
  END IF;
  DELETE FROM public.app_share_control WHERE session_id=sid;
  INSERT INTO public.app_share_control(session_id,generation,peer_id,secret,expires_at) VALUES(sid,gen,pid,translate(encode(extensions.gen_random_bytes(32),'base64'),'+/=','-_'),now()+interval '30 seconds') RETURNING * INTO lease;
  RETURN jsonb_build_object('lease_id',lease.lease_id,'peer_id',lease.peer_id,'expires_at',lease.expires_at,'control_secret',lease.secret);
 END IF;
 IF p_action='controlRenew' THEN
  IF is_host OR peer.role<>'control' OR NOT (prefs->>'auto_control')::boolean THEN RAISE EXCEPTION 'Control denied' USING ERRCODE='42501'; END IF;
  UPDATE public.app_share_control SET expires_at=now()+interval '30 seconds' WHERE session_id=sid AND generation=gen AND peer_id=pid AND lease_id=(p_body->>'lease_id')::uuid AND expires_at>now() RETURNING * INTO lease;
  IF NOT FOUND THEN RAISE EXCEPTION 'Lease expired' USING ERRCODE='42501'; END IF;
  RETURN jsonb_build_object('lease_id',lease.lease_id,'peer_id',lease.peer_id,'expires_at',lease.expires_at,'control_secret',lease.secret);
 END IF;
 IF p_action='controlRelease' THEN
  DELETE FROM public.app_share_control WHERE session_id=sid AND (is_host OR (peer_id=pid AND lease_id=(p_body->>'lease_id')::uuid));
  GET DIAGNOSTICS n=ROW_COUNT;
  RETURN jsonb_build_object('released',n>0);
 END IF;
 IF p_action='controlPoll' THEN
  IF NOT is_host THEN RAISE EXCEPTION 'Host only' USING ERRCODE='42501'; END IF;
  SELECT * INTO lease FROM public.app_share_control WHERE session_id=sid AND expires_at>now();
  IF NOT FOUND OR NOT (prefs->>'auto_control')::boolean THEN RETURN '{"lease":null}'; END IF;
  RETURN jsonb_build_object('lease',jsonb_build_object('lease_id',lease.lease_id,'peer_id',lease.peer_id,'expires_at',lease.expires_at,'control_secret',lease.secret));
 END IF;
 -- Internal fixed SFU adapter bookkeeping is never callable with a client JWT.
 IF auth.role()<>'service_role' OR p_actor_id IS NULL THEN RAISE EXCEPTION 'Internal only' USING ERRCODE='42501'; END IF;
 IF p_action='_mediaAll' THEN
  IF NOT is_host THEN RAISE EXCEPTION 'Host only' USING ERRCODE='42501'; END IF;
  SELECT coalesce(jsonb_agg(to_jsonb(m)),'[]'::jsonb) INTO result FROM public.app_share_media m WHERE session_id=sid;
  RETURN result;
 END IF;
 win:=p_body->>'window_id';
 IF NOT EXISTS(SELECT 1 FROM jsonb_array_elements(s.windows) w WHERE w->>'id'=win) THEN RAISE EXCEPTION 'Window denied' USING ERRCODE='42501'; END IF;
 IF p_action='_mediaLock' THEN
  INSERT INTO public.app_share_media_operations VALUES(sid,pid,win,(p_body->>'operation')::uuid,now()+interval '60 seconds')
   ON CONFLICT(session_id,peer_id,window_id) DO UPDATE SET operation=excluded.operation,expires_at=excluded.expires_at WHERE app_share_media_operations.expires_at<=now();
  GET DIAGNOSTICS n=ROW_COUNT;
  IF n=0 THEN RAISE EXCEPTION 'Operation in progress' USING ERRCODE='PT409'; END IF;
  RETURN '{"locked":true}';
 END IF;
 IF p_action='_mediaUnlock' THEN DELETE FROM public.app_share_media_operations WHERE session_id=sid AND peer_id=pid AND window_id=win AND operation=(p_body->>'operation')::uuid; RETURN '{"unlocked":true}'; END IF;
 IF p_action='_controlAuthorize' THEN
  IF is_host OR peer.role<>'control' OR NOT (prefs->>'auto_control')::boolean OR NOT EXISTS(SELECT 1 FROM public.app_share_control WHERE session_id=sid AND generation=gen AND peer_id=pid AND lease_id=(p_body->>'lease_id')::uuid AND expires_at>now()) THEN RAISE EXCEPTION 'Control denied' USING ERRCODE='42501'; END IF;
  RETURN '{"authorized":true}';
 END IF;
 SELECT * INTO media FROM public.app_share_media WHERE session_id=sid AND peer_id=pid AND window_id=win;
 IF p_action='_mediaGet' THEN RETURN jsonb_build_object('publisher',is_host,'media',CASE WHEN media.sfu_id IS NULL THEN NULL ELSE to_jsonb(media) END,'publication',(SELECT to_jsonb(m) FROM public.app_share_media m WHERE session_id=sid AND generation=gen AND window_id=win AND publisher AND track_name IS NOT NULL),'control_publication',(SELECT to_jsonb(m) FROM public.app_share_media m WHERE session_id=sid AND generation=gen AND window_id=win AND publisher AND channel_id IS NOT NULL)); END IF;
 IF p_action='_mediaBind' THEN
  IF media.sfu_id IS NOT NULL THEN RAISE EXCEPTION 'Media already exists' USING ERRCODE='PT409'; END IF;
  IF coalesce(p_body->>'sfu_id','') !~ '^[A-Za-z0-9_-]{1,128}$' THEN RAISE EXCEPTION 'Invalid media' USING ERRCODE='22023'; END IF;
  INSERT INTO public.app_share_media(session_id,peer_id,window_id,generation,sfu_id,publisher) VALUES(sid,pid,win,gen,p_body->>'sfu_id',is_host);
  RETURN '{"bound":true}';
 END IF;
 IF p_action='_mediaTrack' THEN
  IF media.sfu_id IS NULL OR coalesce(p_body->>'mid','') !~ '^[A-Za-z0-9_-]{1,64}$' THEN RAISE EXCEPTION 'Invalid media' USING ERRCODE='22023'; END IF;
  UPDATE public.app_share_media SET mid=p_body->>'mid',track_name=CASE WHEN is_host THEN p_body->>'track_name' ELSE NULL END WHERE session_id=sid AND peer_id=pid AND window_id=win;
  RETURN '{"bound":true}';
 END IF;
 IF p_action='_dataBind' THEN
  IF media.sfu_id IS NULL OR (p_body->>'channel_id')::integer NOT BETWEEN 0 AND 65534 THEN RAISE EXCEPTION 'Invalid channel' USING ERRCODE='22023'; END IF;
  IF (p_body->>'transport')::boolean THEN UPDATE public.app_share_media SET transport_channel_id=(p_body->>'channel_id')::integer WHERE session_id=sid AND peer_id=pid AND window_id=win;
  ELSE UPDATE public.app_share_media SET channel_id=(p_body->>'channel_id')::integer WHERE session_id=sid AND peer_id=pid AND window_id=win; END IF;
  RETURN '{"bound":true}';
 END IF;
 IF p_action='_mediaDelete' THEN
  -- Called only after confirmed provider closure; avoid queuing already-closed resources.
  UPDATE public.app_share_media SET mid=NULL,channel_id=NULL,transport_channel_id=NULL WHERE session_id=sid AND peer_id=pid AND window_id=win;
  DELETE FROM public.app_share_media WHERE session_id=sid AND peer_id=pid AND window_id=win;
  IF NOT is_host AND NOT EXISTS(SELECT 1 FROM public.app_share_media WHERE session_id=sid AND peer_id=pid) THEN
   DELETE FROM public.app_share_control WHERE session_id=sid AND peer_id=pid;
   DELETE FROM public.app_share_peers WHERE session_id=sid AND id=pid;
  END IF;
  RETURN '{"closed":true}';
 END IF;
 RAISE EXCEPTION 'Unknown action' USING ERRCODE='22023';
END $$;
REVOKE ALL ON FUNCTION public.get_user_app_sharing_preferences(uuid),public.set_user_app_sharing_preferences(boolean,boolean,bigint,uuid),public.app_sharing_command(text,jsonb,uuid) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.get_user_app_sharing_preferences(uuid),public.set_user_app_sharing_preferences(boolean,boolean,bigint,uuid),public.app_sharing_command(text,jsonb,uuid) TO authenticated,service_role;

-- Deploy a scheduler for this bounded metadata reaper; Edge cleanup drains the SFU outbox.
CREATE FUNCTION public.cleanup_expired_app_sharing(p_limit integer DEFAULT 100) RETURNS integer
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE s record; removed integer:=0;
BEGIN
 IF auth.role()<>'service_role' OR p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 100 THEN RAISE EXCEPTION 'Denied' USING ERRCODE='42501'; END IF;
 FOR s IN SELECT id FROM public.app_share_sessions WHERE expires_at<now()-interval '5 minutes' OR EXISTS(SELECT 1 FROM public.app_share_peers p JOIN public.app_share_media m ON m.session_id=p.session_id AND m.peer_id=p.id WHERE p.session_id=app_share_sessions.id AND p.expires_at<=now()) ORDER BY expires_at LIMIT p_limit FOR UPDATE SKIP LOCKED LOOP
  PERFORM public.retire_app_sharing_generation(s.id);
  DELETE FROM public.app_share_sessions WHERE id=s.id AND expires_at<now()-interval '5 minutes';
  removed:=removed+1;
 END LOOP;
 RETURN removed;
END $$;
REVOKE ALL ON FUNCTION public.cleanup_expired_app_sharing(integer) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.cleanup_expired_app_sharing(integer) TO service_role;
CREATE INDEX app_share_sessions_expiry ON public.app_share_sessions(expires_at);

ALTER TABLE public.app_share_media_cleanup ADD COLUMN attempts integer NOT NULL DEFAULT 0;
ALTER TABLE public.app_share_media_cleanup ADD COLUMN next_attempt_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE public.app_share_media_cleanup ADD COLUMN claim uuid;
CREATE INDEX app_share_media_cleanup_due ON public.app_share_media_cleanup(next_attempt_at,id);
CREATE TABLE public.app_share_maintenance_nonces(nonce uuid PRIMARY KEY,expires_at timestamptz NOT NULL);
ALTER TABLE public.app_share_maintenance_nonces ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.app_share_maintenance_nonces FROM PUBLIC,anon,authenticated;
CREATE INDEX app_share_maintenance_nonce_expiry ON public.app_share_maintenance_nonces(expires_at);
CREATE FUNCTION public.claim_app_sharing_cleanup(p_nonce uuid,p_timestamp bigint,p_limit integer DEFAULT 8) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE result jsonb; changed integer;
BEGIN
 IF auth.role()<>'service_role' OR p_nonce IS NULL OR p_timestamp IS NULL OR abs(extract(epoch FROM clock_timestamp())-p_timestamp)>60 OR p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 8 THEN RAISE EXCEPTION 'Denied' USING ERRCODE='42501'; END IF;
 DELETE FROM public.app_share_maintenance_nonces WHERE nonce IN (SELECT nonce FROM public.app_share_maintenance_nonces WHERE expires_at<now() LIMIT 100);
 INSERT INTO public.app_share_maintenance_nonces VALUES(p_nonce,now()+interval '2 minutes') ON CONFLICT DO NOTHING;
 GET DIAGNOSTICS changed=ROW_COUNT;
 IF changed=0 THEN RAISE EXCEPTION 'Replay denied' USING ERRCODE='42501'; END IF;
 PERFORM public.cleanup_expired_app_sharing(100);
 WITH selected AS (SELECT id FROM public.app_share_media_cleanup WHERE next_attempt_at<=now() ORDER BY next_attempt_at,id LIMIT p_limit FOR UPDATE SKIP LOCKED),
 claimed AS (UPDATE public.app_share_media_cleanup c SET claim=p_nonce,attempts=least(attempts+1,1000000),next_attempt_at=now()+make_interval(secs=>least(3600,60*(1<<least(c.attempts,6)))) FROM selected WHERE c.id=selected.id RETURNING c.id,c.claim,c.descriptor)
 SELECT coalesce(jsonb_agg(to_jsonb(claimed)),'[]'::jsonb) INTO result FROM claimed;
 RETURN jsonb_build_object('jobs',result);
END $$;
CREATE FUNCTION public.ack_app_sharing_cleanup(p_id bigint,p_claim uuid) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE changed integer;
BEGIN
 IF auth.role()<>'service_role' THEN RAISE EXCEPTION 'Denied' USING ERRCODE='42501'; END IF;
 DELETE FROM public.app_share_media_cleanup WHERE id=p_id AND claim=p_claim;
 GET DIAGNOSTICS changed=ROW_COUNT;
 RETURN changed>0;
END $$;
REVOKE ALL ON FUNCTION public.claim_app_sharing_cleanup(uuid,bigint,integer),public.ack_app_sharing_cleanup(bigint,uuid) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.claim_app_sharing_cleanup(uuid,bigint,integer),public.ack_app_sharing_cleanup(bigint,uuid) TO service_role;
