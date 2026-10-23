-- Bound LiveKit room creation across edge isolates. One start per account per 30 seconds;
-- a stable LiveKit room name separately prevents overlapping calls after this lease expires.
BEGIN;
CREATE TABLE public.boss_ai_voice_starts (
  user_id uuid PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
  started_at timestamptz NOT NULL
);
ALTER TABLE public.boss_ai_voice_starts ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.boss_ai_voice_starts FROM PUBLIC, anon, authenticated;
CREATE FUNCTION public.boss_ai_claim_voice_start(p_user_id uuid)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE claimed uuid;
BEGIN
  IF public.boss_ai_policy(p_user_id, 'max-stt') IS NULL THEN RETURN false; END IF;
  INSERT INTO public.boss_ai_voice_starts(user_id, started_at)
    VALUES (p_user_id, clock_timestamp())
  ON CONFLICT (user_id) DO UPDATE SET started_at = EXCLUDED.started_at
    WHERE boss_ai_voice_starts.started_at <= clock_timestamp() - interval '30 seconds'
  RETURNING user_id INTO claimed;
  RETURN claimed IS NOT NULL;
END;
$$;
REVOKE ALL ON FUNCTION public.boss_ai_claim_voice_start(uuid) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.boss_ai_claim_voice_start(uuid) TO service_role;
COMMIT;
