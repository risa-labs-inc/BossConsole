-- Collapse the bridge viewer's mediaCreate + mediaSubscribe database chatter
-- while preserving the existing authorization and operation-lock commands.
CREATE FUNCTION public.fluck_web_begin_media_subscribe(
 p_grant text,
 p_session_id uuid,
 p_generation uuid,
 p_peer_id uuid,
 p_window_id text,
 p_operation uuid
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid; request jsonb; state jsonb;
BEGIN
 IF auth.role()<>'service_role' THEN RAISE EXCEPTION 'Denied' USING ERRCODE='42501'; END IF;
 actor:=public.fluck_web_authorize_app_sharing(
  p_grant,p_session_id,p_generation,'mediaSubscribe',NULL
 );
 request:=jsonb_build_object(
  'session_id',p_session_id,'generation',p_generation,'peer_id',p_peer_id,
  'window_id',p_window_id,'operation',p_operation
 );
 PERFORM public.app_sharing_command('_mediaLock',request,actor);
 state:=public.app_sharing_command('_mediaGet',request,actor);
 IF coalesce((state->>'publisher')::boolean,false) OR state->'media'<>'null'::jsonb OR state->'publication' IS NULL OR state->'publication'='null'::jsonb THEN
  RAISE EXCEPTION 'Conflict' USING ERRCODE='PT409';
 END IF;
 RETURN jsonb_build_object('actor',actor,'state',state);
END $$;

CREATE FUNCTION public.fluck_web_commit_media_subscribe(
 p_actor uuid,
 p_session_id uuid,
 p_generation uuid,
 p_peer_id uuid,
 p_window_id text,
 p_operation uuid,
 p_sfu_id text,
 p_mid text
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE request jsonb;
BEGIN
 IF auth.role()<>'service_role' THEN RAISE EXCEPTION 'Denied' USING ERRCODE='42501'; END IF;
 request:=jsonb_build_object(
  'session_id',p_session_id,'generation',p_generation,'peer_id',p_peer_id,
  'window_id',p_window_id,'operation',p_operation
 );
 PERFORM public.app_sharing_command('_mediaBind',request||jsonb_build_object('sfu_id',p_sfu_id),p_actor);
 PERFORM public.app_sharing_command('_mediaTrack',request||jsonb_build_object('mid',p_mid),p_actor);
 PERFORM public.app_sharing_command('_mediaUnlock',request,p_actor);
 RETURN '{"committed":true}'::jsonb;
END $$;

REVOKE ALL ON FUNCTION public.fluck_web_begin_media_subscribe(text,uuid,uuid,uuid,text,uuid) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.fluck_web_begin_media_subscribe(text,uuid,uuid,uuid,text,uuid) TO service_role;
REVOKE ALL ON FUNCTION public.fluck_web_commit_media_subscribe(uuid,uuid,uuid,uuid,text,uuid,text,text) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.fluck_web_commit_media_subscribe(uuid,uuid,uuid,uuid,text,uuid,text,text) TO service_role;
