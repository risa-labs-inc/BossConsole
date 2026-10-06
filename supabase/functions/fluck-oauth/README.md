# Fluck Google OAuth callback

The OAuth redirect target for Fluck's Google connector. It verifies a `state` signed by a Fluck
install's registered Ed25519 key, exchanges the authorization code with Google, and writes the
resulting refresh token into BOSS Secret Manager as the BOSS user who owns that install. It is also
where installs refresh access tokens, since the Google web client secret lives only here.

It replaces a callback Fluck used to serve from the machine it runs on, behind a Tailscale Funnel
hostname. A redirect URI registered with Google has to keep resolving forever; a tailnet hostname
ties that to one machine staying awake and staying put, and an edge function does not.

## The flow

1. `connect_service google` on the plugin mints a state token, signed with the install's link key,
   and builds a Google authorization URL. The model texts the link to the user. Nothing is stored
   yet.
2. The user taps it and approves on Google's own page.
3. Google redirects the phone browser to `GET /callback?code=…&state=…`.
4. This function looks the install up in `fluck_vault_instances`, requires it unrevoked and owned by
   the state's `uid`, verifies the signature, claims its nonce once, exchanges the code, and stores
   the refresh token at `fluck/<workspaceId>/google/GOOGLE_REFRESH_TOKEN`. Before that write it
   binds the token to the state's `uid` in `fluck_oauth_grants` (see below); a failed bind fails the
   callback and writes no secret.
5. The browser gets one sentence. The plugin, which has been polling the vault for that key every
   five seconds, sees it arrive, enables the Google connectors for that workspace, and tells the
   user in Messages.

The plugin never learns the code and this function never learns the conversation. The only thing
that crosses between them is the secret, through the Secret Manager.

## Routes

| Route           | Auth                | What it does                                                                    |
| --------------- | ------------------- | ------------------------------------------------------------------------------- |
| `GET /callback` | the signed `state`  | Verifies, exchanges, stores, renders one sentence                               |
| `POST /refresh` | signed install call | Refresh token in, access token out; nothing stored or logged                    |
| `GET /health`   | none                | `{ ok, configured: { clientId, clientSecret, githubClientId } }`, booleans only |
| `GET /client`   | none                | `{ client_id, github_client_id? }`, the public ids the plugin needs             |

`verify_jwt = false` in `supabase/config.toml`, and it must be: the callback caller is a browser
following a redirect Google issued and carries no header we chose, and `/refresh` callers hold no
Supabase credential. The `state`, and for `/refresh` the request signature, is the entire
authentication.

### `POST /refresh`

Signed exactly like fluck-vault's machine routes: `X-Fluck-Instance`, `X-Fluck-Timestamp` (unix
seconds, 120 s skew) and `X-Fluck-Signature` (base64url Ed25519 by the install's link key) over

```
fluck-vault-signed-v1\nPOST\n/refresh\n<ts>\n<sha256 hex of the raw body>
```

Body `{"refresh_token":"…"}`. Answers:

| Status | Body                                                 | Meaning                                   |
| ------ | ---------------------------------------------------- | ----------------------------------------- |
| 200    | `{"access_token":"…","expires_in":3599,"scope":"…"}` | Fresh access token                        |
| 400    | `{"error":"invalid_grant"}`                          | Grant dead or not bound to you; reconnect |
| 400    | `{"error":"body"}`                                   | Signed, but not a refresh request         |
| 401    | `{"error":"unauthorized"}`                           | Any auth failure, deliberately uniform    |
| 502    | `{"error":"unavailable"}`                            | Any other Google refusal, or an outage    |
| 503    | `{"error":"unconfigured"}`                           | Client id or secret not set here          |

### Grant binding

The callback records the SHA-256 of every refresh token it issues, with the BOSS user it was issued
to, in `public.fluck_oauth_grants` (never the token itself). `/refresh` redeems a token only if its
hash is bound to the user who owns the calling install. An unbound token, or one bound to another
user, gets `invalid_grant` without Google being called, and logs
`refresh refused: unbound [<install id prefix>]`. Without this, any BOSS user who registered an
install could redeem a stolen refresh token with our client secret. When Google itself answers
`invalid_grant`, the binding is deleted. A failure to read the binding is a 502, not
`invalid_grant`, so a database blip never makes the plugin drop a live grant.

Grants made before the binding existed have no row, so their first refresh after the deploy is
`invalid_grant` and the plugin asks the owner to reconnect once. That is the intended migration for
them.

## Environment

Set with `supabase secrets set --project-ref pcnwqamqdnsadranufjv …`. `SUPABASE_URL` and
`SUPABASE_SERVICE_ROLE_KEY` are injected by the platform and are not set by hand.

| Variable                   | Required | What it is                                                                                                                 |
| -------------------------- | -------- | -------------------------------------------------------------------------------------------------------------------------- |
| `GOOGLE_WEB_CLIENT_ID`     | yes      | The Google OAuth client of type **Web application**                                                                        |
| `GOOGLE_WEB_CLIENT_SECRET` | yes      | Its client secret. This function is the only holder; the plugin never sees it                                              |
| `GITHUB_OAUTH_CLIENT_ID`   | no       | Public client id of the risa-labs-inc GitHub OAuth App with Device Flow enabled; served at `/client` as `github_client_id` |
| `PUBLIC_BASE_URL`          | no       | Defaults to `https://pcnwqamqdnsadranufjv.functions.supabase.co/fluck-oauth`. Set it when the custom domain lands          |

There is no state key. Each install signs with the Ed25519 key it registered through fluck-vault's
`POST /instances`, and the install row (not the token) decides whose secrets it may write.
`FLUCK_STATE_KEY` and `FLUCK_USER_ID` are gone; unset them after deploying.

### `PUBLIC_BASE_URL`

The redirect URI is this value plus `/callback`, and Google compares it byte for byte with the one
in the authorization request. It is therefore a configuration value at BOTH ends: change it here and
in the plugin's `FLUCK_OAUTH_PUBLIC_BASE_URL`, add the new URI to the Google client, and only then
remove the old one.

## Google console

The client is a **Web application** client. Add exactly this to its Authorised redirect URIs:

```
https://pcnwqamqdnsadranufjv.functions.supabase.co/fluck-oauth/callback
```

Byte for byte, no trailing slash. Enable the Gmail, Calendar, Drive, Docs and Sheets APIs on the
same project. The scopes the plugin requests are `gmail.modify`, `calendar`, `drive.file`,
`documents`, `spreadsheets`, `openid` and `userinfo.email`, with `access_type=offline` and
`prompt=consent`, which together are what make Google return a refresh token every time rather than
only on the first consent an account ever gives.

## No PKCE

Deliberate. The party that exchanges the code is this function, which is a confidential client
holding `GOOGLE_WEB_CLIENT_SECRET` in its own environment, and that is precisely what PKCE
substitutes for in a public client. A verifier would have had to travel here inside the same signed
state that already accompanies the request, adding a second secret to keep and no binding the
signature does not already provide. The CSRF property is covered by the state being signed, single
use and short lived.

## Deployment

1. Apply the migrations, which create `public.fluck_oauth_nonces`, `public.fluck_oauth_claim_nonce`,
   `public.fluck_oauth_store_secret`, and (from fluck-vault) `public.fluck_vault_instance`:

   Also apply `20260925080000_fluck_oauth_nonce_clock_skew.sql` before deploying this function. It
   permits one minute of database/edge clock skew while retaining replay records for the same extra
   minute. Invalid RPC input raises `22023` (rendered as an invalid link); only a nonce conflict is
   reported as replay. The edge still independently enforces the signed state's 15-minute lifetime
   and expiry, so this database tolerance does not extend a link's validity.

   ```sh
   supabase db push --project-ref pcnwqamqdnsadranufjv
   # or, to apply this one file against a linked project:
   # psql "$DATABASE_URL" -f supabase/migrations/20260924230000_fluck_oauth.sql
   ```

   Also apply `20260929110000_fluck_oauth_grants.sql` (the grant bindings) BEFORE deploying the
   function version that binds grants: that version calls `fluck_oauth_bind_grant` on every callback
   and `fluck_oauth_grant_owner` on every refresh, and without the migration every callback fails
   and every refresh is a 502.

2. Set the environment:

   ```sh
   supabase secrets set --project-ref pcnwqamqdnsadranufjv \
     GOOGLE_WEB_CLIENT_ID=294223497390-6ndgin5tjc8oqkqn7gc16n28rmkjvjp5.apps.googleusercontent.com
   supabase secrets set --project-ref pcnwqamqdnsadranufjv GOOGLE_WEB_CLIENT_SECRET='…'
   ```

3. Deploy:

   ```sh
   supabase functions deploy fluck-oauth --project-ref pcnwqamqdnsadranufjv
   ```

4. Check it:

   ```sh
   curl -s https://pcnwqamqdnsadranufjv.functions.supabase.co/fluck-oauth/health
   # {"ok":true,"configured":{"clientId":true,"clientSecret":true}}
   ```

## The state format is a shared contract

`tests/fluck-oauth-state.fixture.json` is byte identical to the copy in the fluck-agent-imessage
repo under `src/test/resources/`: a fixed Ed25519 seed, the payload bytes and the token. Ed25519 is
deterministic, so both suites mint the same token from the seed and verify it. Change the signing
prefix or the claim order in one place and the other suite goes red, which is the point: the
alternative is a deployment that looks clean and refuses every callback.

## Tests

```sh
cd supabase/functions/fluck-oauth
deno task test    # deno test --allow-env --allow-read
deno task check   # deno fmt --check && deno check index.ts tests/*.test.ts
```

The tests drive `createHandler` from `app.ts`, never `index.ts`, so nothing binds a port and no
Supabase client is constructed. Google's token endpoint is a fake `fetch` that records what was
sent, so the redirect URI and the absence of a `code_verifier` are asserted as facts about the
request rather than as a reading of the source.

## What is never written down

No code, no token, no email, no state, in any log line or any response body. A log line is the
route, the outcome, and the first eight characters of the workspace id, which is enough to line two
attempts up against each other in a log and nothing else. A test asserts it.
