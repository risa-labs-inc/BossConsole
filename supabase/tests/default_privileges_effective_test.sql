BEGIN;
SELECT plan(7);

-- Setup: Create scratch objects as postgres to simulate a future migration
SET ROLE postgres;
CREATE TABLE public._scratch_test_table (id int);
CREATE SEQUENCE public._scratch_test_seq;
CREATE FUNCTION public._scratch_test_func() RETURNS int AS $$ SELECT 1 $$ LANGUAGE sql;
RESET ROLE;

-- Verify Effective Table Privileges
SELECT ok(
    NOT has_table_privilege('anon', 'public._scratch_test_table', 'SELECT'), 
    'New table has no implicit anon privileges'
);
SELECT ok(
    NOT has_table_privilege('authenticated', 'public._scratch_test_table', 'SELECT'), 
    'New table has no implicit authenticated privileges'
);

-- Verify Effective Sequence Privileges
SELECT ok(
    NOT has_sequence_privilege('anon', 'public._scratch_test_seq', 'USAGE'), 
    'New sequence has no implicit anon privileges'
);
SELECT ok(
    NOT has_sequence_privilege('authenticated', 'public._scratch_test_seq', 'USAGE'), 
    'New sequence has no implicit authenticated privileges'
);

-- Verify Effective Function Privileges (Defends against PUBLIC built-in)
SELECT ok(
    NOT has_function_privilege('anon', 'public._scratch_test_func()', 'EXECUTE'), 
    'New function has no implicit anon EXECUTE'
);
SELECT ok(
    NOT has_function_privilege('authenticated', 'public._scratch_test_func()', 'EXECUTE'), 
    'New function has no implicit authenticated EXECUTE'
);

-- Verify Owner Baseline
SELECT ok(
    has_table_privilege('postgres', 'public._scratch_test_table', 'ALL'), 
    'postgres retains expected ownership control'
);

SELECT * FROM finish();
ROLLBACK;
