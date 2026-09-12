BEGIN;

SELECT plan(11);

-- These objects simulate objects created after the restrictive default
-- privileges migration.

CREATE SEQUENCE public.default_privilege_guard_sequence;

CREATE TABLE public.default_privilege_guard_table (
    id bigint
);

CREATE OR REPLACE FUNCTION public.default_privilege_guard_function()
RETURNS integer
LANGUAGE sql
AS $$
    SELECT 1;
$$;

-- Table privileges must not be inherited by client roles.
SELECT ok(
    NOT (
        has_table_privilege('anon', 'public.default_privilege_guard_table', 'SELECT')
        OR has_table_privilege('anon', 'public.default_privilege_guard_table', 'INSERT')
        OR has_table_privilege('anon', 'public.default_privilege_guard_table', 'UPDATE')
        OR has_table_privilege('anon', 'public.default_privilege_guard_table', 'DELETE')
        OR has_table_privilege('anon', 'public.default_privilege_guard_table', 'TRUNCATE')
        OR has_table_privilege('anon', 'public.default_privilege_guard_table', 'REFERENCES')
        OR has_table_privilege('anon', 'public.default_privilege_guard_table', 'TRIGGER')
    ),
    'anon has no default ALL privilege on new tables'
);

SELECT ok(
    NOT (
        has_table_privilege('authenticated', 'public.default_privilege_guard_table', 'SELECT')
        OR has_table_privilege('authenticated', 'public.default_privilege_guard_table', 'INSERT')
        OR has_table_privilege('authenticated', 'public.default_privilege_guard_table', 'UPDATE')
        OR has_table_privilege('authenticated', 'public.default_privilege_guard_table', 'DELETE')
        OR has_table_privilege('authenticated', 'public.default_privilege_guard_table', 'TRUNCATE')
        OR has_table_privilege('authenticated', 'public.default_privilege_guard_table', 'REFERENCES')
        OR has_table_privilege('authenticated', 'public.default_privilege_guard_table', 'TRIGGER')
    ),
    'authenticated has no default ALL privilege on new tables'
);

-- Sequence privileges must not be inherited by client roles.
SELECT ok(
    NOT has_sequence_privilege(
        'anon',
        'public.default_privilege_guard_sequence',
        'USAGE'
    )
    AND NOT has_sequence_privilege(
        'anon',
        'public.default_privilege_guard_sequence',
        'SELECT'
    )
    AND NOT has_sequence_privilege(
        'anon',
        'public.default_privilege_guard_sequence',
        'UPDATE'
    ),
    'anon has no default privilege on new sequences'
);

SELECT ok(
    NOT has_sequence_privilege(
        'authenticated',
        'public.default_privilege_guard_sequence',
        'USAGE'
    )
    AND NOT has_sequence_privilege(
        'authenticated',
        'public.default_privilege_guard_sequence',
        'SELECT'
    )
    AND NOT has_sequence_privilege(
        'authenticated',
        'public.default_privilege_guard_sequence',
        'UPDATE'
    ),
    'authenticated has no default privilege on new sequences'
);

-- Function privileges must not be inherited by client roles.
SELECT ok(
    NOT has_function_privilege(
        'anon',
        'public.default_privilege_guard_function()',
        'EXECUTE'
    ),
    'anon has no default EXECUTE privilege on new functions'
);

SELECT ok(
    NOT has_function_privilege(
        'authenticated',
        'public.default_privilege_guard_function()',
        'EXECUTE'
    ),
    'authenticated has no default EXECUTE privilege on new functions'
);

-- The owner must still retain access.
SELECT ok(
    has_table_privilege(
        'postgres',
        'public.default_privilege_guard_table',
        'SELECT'
    ),
    'postgres retains SELECT privilege on new tables'
);

SELECT ok(
    has_sequence_privilege(
        'postgres',
        'public.default_privilege_guard_sequence',
        'USAGE'
    ),
    'postgres retains USAGE privilege on new sequences'
);

SELECT ok(
    has_function_privilege(
        'postgres',
        'public.default_privilege_guard_function()',
        'EXECUTE'
    ),
    'postgres retains EXECUTE privilege on new functions'
);

-- The signup trigger must not be directly callable by client roles.
SELECT ok(
    NOT has_function_privilege(
        'anon',
        'public.handle_new_user()',
        'EXECUTE'
    ),
    'anon cannot execute the internal handle_new_user trigger'
);

SELECT ok(
    NOT has_function_privilege(
        'authenticated',
        'public.handle_new_user()',
        'EXECUTE'
    ),
    'authenticated cannot execute the internal handle_new_user trigger'
);

SELECT * FROM finish();

ROLLBACK;
