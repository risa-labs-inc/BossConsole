-- Check if session_id column exists in completed_authentications
SELECT column_name, data_type, is_nullable
FROM information_schema.columns
WHERE table_schema = 'public'
  AND table_name = 'completed_authentications'
ORDER BY ordinal_position;
