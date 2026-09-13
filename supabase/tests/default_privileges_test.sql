BEGIN;

SELECT plan(17);

-- These objects simulate objects created after the restrictive default
-- privileges migration.

-- Explicit migration owner, carried over from @johncybersage in #592.
SET ROLE postgres;
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

RESET ROLE;

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

-- service_role remains the intended server caller after defaults change.
SELECT ok((SELECT bool_and(has_table_privilege('service_role',
    'public.default_privilege_guard_table', privilege))
    FROM unnest(ARRAY['SELECT','INSERT','UPDATE','DELETE','TRUNCATE','REFERENCES','TRIGGER']) AS p(privilege)),
    'service_role retains table access');
SELECT ok(has_sequence_privilege('service_role', 'public.default_privilege_guard_sequence', 'USAGE'),
    'service_role retains sequence access');
SELECT ok(has_function_privilege('service_role', 'public.default_privilege_guard_function()', 'EXECUTE'),
    'service_role retains function access');

-- Default ACLs must not silently restore client grants behind the event guard.
SELECT ok(NOT EXISTS (
    SELECT 1 FROM pg_default_acl d
    CROSS JOIN LATERAL aclexplode(d.defaclacl) a
    WHERE d.defaclrole = 'postgres'::regrole
      AND d.defaclnamespace = 'public'::regnamespace
      AND d.defaclobjtype IN ('r', 'S', 'f')
      AND a.grantee IN ('anon'::regrole, 'authenticated'::regrole)
), 'public defaults contain no client grants');

-- The public-schema guard must not change functions in other schemas.
SET ROLE postgres;
CREATE SCHEMA default_privilege_guard_schema;
CREATE FUNCTION default_privilege_guard_schema.probe() RETURNS integer
LANGUAGE sql AS $$ SELECT 1 $$;
RESET ROLE;
SELECT ok(has_function_privilege('anon', 'default_privilege_guard_schema.probe()', 'EXECUTE'),
    'outside-public function retains built-in PUBLIC EXECUTE for anon');
SELECT ok(has_function_privilege('authenticated', 'default_privilege_guard_schema.probe()', 'EXECUTE'),
    'outside-public function retains built-in PUBLIC EXECUTE for authenticated');

SELECT * FROM finish();

ROLLBACK;
