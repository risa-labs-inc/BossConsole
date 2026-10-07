# Isolated application sharing validation and rollout

The linked project `pcnwqamqdnsadranufjv` is shared production, including when
accessed through a debug hostname. Do not run `supabase db push` against it as
part of local validation.

## Local real-service test

Run these commands from the repository root. They use only a new, unlinked
`/tmp/boss-app-sharing-e2e` stack on API 55431 and database 55432, with the
app-only migration. The preparation script refuses to overwrite an existing
stack. No shared project is reset or linked.

```sh
node scripts/app-sharing/prepare-local.mjs
umask 077
export DOCKER_HOST=unix:///Users/kshivang/.docker/run/docker.sock
export DOCKER_CONFIG=/tmp/boss-app-sharing-e2e/docker
supabase start --workdir /tmp/boss-app-sharing-e2e > /tmp/boss-app-sharing-e2e/start.log 2>&1
supabase functions serve --workdir /tmp/boss-app-sharing-e2e \
  --env-file /tmp/boss-app-sharing-e2e/functions.env \
  > /tmp/boss-app-sharing-e2e/functions.log 2>&1 &
BOSS_APP_TEST_STACK=/tmp/boss-app-sharing-e2e \
BOSS_TEST_APP_SFU_CREDENTIALS=/tmp/boss-app-sharing-e2e/credentials.json \
node scripts/app-sharing/local-api-smoke.mjs
node scripts/app-sharing/local-maintenance-smoke.mjs
```

Use the applicable Docker socket on other machines. The isolated empty Docker
config avoids a desktop credential-helper prompt for public images; it does not
modify the user's Docker configuration. Wait for function serving before the
smoke scripts. Logs may contain local credentials; keep them owner-only.

The generated `functions.env` contains a separate random maintenance HMAC key.
Live provider validation additionally requires a dedicated Cloudflare Realtime
SFU application and its **app ID + app secret**, configured as
`APP_SHARING_SFU_APP_ID` and `APP_SHARING_SFU_SECRET` in that owner-only file.
Provisioning an application requires account **Calls: Edit** (`CallsEdit`)
permission. A Workers-only Wrangler OAuth login is insufficient. A provisioning
API token is not interchangeable with the returned SFU app secret. Restart only
the isolated function server after adding these server-only credentials. Do not
print either credential or put it in a browser/JVM configuration.

The script refuses any API hostname/port except `127.0.0.1:55431`. It creates
synthetic GoTrue users, signs in through the real password endpoint and tests
Edge/PostgREST authorization, replay rejection, roles, settings CAS and session
retirement. It deletes the secondary test account and keeps only the dedicated
harness account when a credentials file is requested. The credentials file is
mode 0600 and matches the live media harness contract. Delete that synthetic
user through the local GoTrue admin API and remove the credentials file after
the media harness completes. Without the credentials output environment variable,
both synthetic accounts are deleted automatically.

## Controlled hosted rollout (not yet performed)

1. Review the exact app-only migration and confirm it is absent from migration
   history. Back up the target database. Apply only that reviewed file in one
   transaction with `psql --single-transaction --set ON_ERROR_STOP=1 --file=...`.
   Record only version `20260928000000` as applied after successful execution.
   Do not apply unrelated pending migrations.
2. Configure dedicated test SFU credentials and a separate maintenance HMAC
   secret in the target Edge project's secret store. Never use either credential
   in a browser, Kotlin config, query parameter, or commit.
3. Deploy `app-sharing` and `app-sharing-maintenance`, then additive
   `live-sessions` and `user-settings` updates. Existing terminal APIs and ticket
   formats remain unchanged. Run terminal regression tests and existing deployed
   terminal smoke checks before enabling the client cohort.
4. Schedule `node scripts/app-sharing-maintenance.mjs` once per minute using a
   trusted scheduler. Supply `APP_SHARING_MAINTENANCE_URL` as the fixed HTTPS
   function URL and `APP_SHARING_MAINTENANCE_KEY` through secret storage. The
   scheduler needs no database/service-role or SFU credentials. Its requests use
   timestamped HMAC + database nonce replay rejection. Alert on nonzero exit or
   repeated failed jobs; the script prints only claimed/closed/failed counts.
   For a trusted Linux scheduler, an explicit cron entry is:
   `* * * * * /opt/boss/run-app-sharing-maintenance`.
   That owner-only wrapper loads the two named variables from the scheduler's
   secret store and invokes the repository script using an absolute Node path.
   Do not put secrets directly in crontab. Activation is still pending.
5. Verify an authenticated scheduler invocation, replay rejection and a provider
   cleanup job completion. Confirm later jobs continue while one job retries.
6. Enable only explicit local sharing in a small test cohort. Validate real SFU
   publisher/subscriber media, control lease turnover, same-account browser
   login, logout, expiry, revocation, screen lock and app shutdown. Source/unit
   tests alone do not satisfy these gates.

Rollback starts by stopping local publications and disabling the new client
feature. Keep maintenance running until the outbox is drained. The additive
schema can remain without affecting terminal sessions. Never drop tables merely
to disable sharing while provider cleanup is still pending.
