-- Collapse Fluck's owner-authorized register/admit/consume startup into one
-- service-role database round trip. Native bearer/JWT app-sharing is unchanged.
CREATE FUNCTION public.fluck_web_bootstrap_app_sharing(
 p_grant text,
 p_session_id uuid,
 p_generation uuid,
 p_instance_id text,
 p_device_id uuid,
 p_name text,
 p_windows jsonb,
 p_key_epoch uuid,
 p_host_public_key text,
 p_role text
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE
 actor uuid;
 registered jsonb;
 admitted jsonb;
 consumed jsonb;
 descriptor jsonb;
BEGIN
 actor:=public.fluck_web_authorize_app_sharing(
  p_grant,p_session_id,p_generation,'register',p_instance_id
 );
 descriptor:=jsonb_build_object(
  'session_id',p_session_id,
  'generation',p_generation,
  'instance_id',p_instance_id,
  'device_id',p_device_id,
  'name',p_name,
  'windows',p_windows,
  'viewer_url','https://bridge.invalid/#k=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA',
  'key_epoch',p_key_epoch,
  'host_public_key',p_host_public_key
 );
 registered:=public.app_sharing_command('register',descriptor,actor);
 admitted:=public.app_sharing_command(
  'admit',
  jsonb_build_object(
   'session_id',p_session_id,
   'generation',p_generation,
   'device_id',p_device_id,
   'role',p_role
  ),
  actor
 );
 consumed:=public.app_sharing_command(
  'consume',
  jsonb_build_object(
   'session_id',p_session_id,
   'generation',p_generation,
   'device_id',p_device_id,
   'role',p_role,
   'ticket',admitted->>'ticket'
  ),
  actor
 );
 RETURN jsonb_build_object(
  'host_peer_id',registered->>'host_peer_id',
  'viewer_peer_id',consumed->>'peer_id'
 );
END $$;

REVOKE ALL ON FUNCTION public.fluck_web_bootstrap_app_sharing(text,uuid,uuid,text,uuid,text,jsonb,uuid,text,text) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.fluck_web_bootstrap_app_sharing(text,uuid,uuid,text,uuid,text,jsonb,uuid,text,text) TO service_role;
