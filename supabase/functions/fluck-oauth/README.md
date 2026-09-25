# Fluck Google OAuth callback

The OAuth redirect target for Fluck's Google connector. It verifies a signed `state` minted by the
Fluck plugin, exchanges the authorization code with Google, and writes the resulting refresh token
into BOSS Secret Manager as the BOSS user named in that state.

It replaces a callback Fluck used to serve from the machine it runs on, behind a Tailscale Funnel
hostname. A redirect URI registered with Google has to keep resolving forever; a tailnet hostname
ties that to one machine staying awake and staying put, and an edge function does not.

## The flow

1. `connect_service google` on the plugin mints a state token and builds a Google authorization URL.
   The model texts the link to the user. Nothing is stored yet.
2. The user taps it and approves on Google's own page.
3. Google redirects the phone browser to `GET /callback?code=…&state=…`.
4. This function verifies the state, claims its nonce once, exchanges the code, and stores the
   refresh token at `fluck/<workspaceId>/google/GOOGLE_REFRESH_TOKEN`.
5. The browser gets one sentence. The plugin, which has been polling the vault for that key every
   five seconds, sees it arrive, enables the Google connectors for that workspace, and tells the
   user in Messages.

The plugin never learns the code and this function never learns the conversation. The only thing
that crosses between them is the secret, through the Secret Manager.

## Routes

| Route           | Auth               | What it does                                                                |
| --------------- | ------------------ | --------------------------------------------------------------------------- |
| `GET /callback` | the signed `state` | Verifies, exchanges, stores, renders one sentence                           |
| `GET /health`   | none               | `{ ok, configured: { clientId, clientSecret, stateKey } }` as booleans only |

`verify_jwt = false` in `supabase/config.toml`, and it must be: the caller is a browser following a
redirect Google issued and carries no header we chose. The `state` is the entire authentication.

## Environment

Set with `supabase secrets set --project-ref pcnwqamqdnsadranufjv …`. `SUPABASE_URL` and
`SUPABASE_SERVICE_ROLE_KEY` are injected by the platform and are not set by hand.

| Variable                   | Required | What it is                                                                                                        |
| -------------------------- | -------- | ----------------------------------------------------------------------------------------------------------------- |
| `GOOGLE_WEB_CLIENT_ID`     | yes      | The Google OAuth client of type **Web application**                                                               |
| `GOOGLE_WEB_CLIENT_SECRET` | yes      | Its client secret. This function is the only holder; the plugin never sees it                                     |
| `FLUCK_STATE_KEY`          | yes      | 32 random bytes, base64url encoded. Must equal the BOSS secret `fluck-state-key`                                  |
| `FLUCK_USER_ID`            | yes      | BOSS user UUID authorized for this signing key. Other signed user IDs are refused before any side effects.        |
| `PUBLIC_BASE_URL`          | no       | Defaults to `https://pcnwqamqdnsadranufjv.functions.supabase.co/fluck-oauth`. Set it when the custom domain lands |

### `FLUCK_STATE_KEY`

The plugin generates this on first use and stores it in BOSS Secret Manager under the website
`fluck-state-key`. Copying it here is a **manual step the owner performs once**, and there is no
automatic path for it by design: a function that could read the key out of the vault would be a
function that could read every secret in the vault.

To read it, open Secret Manager in BOSS, find `fluck-state-key`, and set the same value here. If the
two ever disagree, every callback answers "That sign in link is no longer valid" and nothing else
breaks, which is the failure mode this was chosen for.

This deployment serves one BOSS user. Set `FLUCK_USER_ID` to that user's UUID alongside the key. The
desktop holds the signing key and can mint arbitrary claims; the server-side user binding prevents
that key from overwriting another user's connector secrets. Serving multiple users requires separate
key-to-user bindings, not sharing one key among them.

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

1. Apply the migration, which creates `public.fluck_oauth_nonces`, `public.fluck_oauth_claim_nonce`
   and `public.fluck_oauth_store_secret`:

   ```sh
   supabase db push --project-ref pcnwqamqdnsadranufjv
   # or, to apply this one file against a linked project:
   # psql "$DATABASE_URL" -f supabase/migrations/20260924230000_fluck_oauth.sql
   ```

2. Set the environment:

   ```sh
   supabase secrets set --project-ref pcnwqamqdnsadranufjv \
     GOOGLE_WEB_CLIENT_ID=294223497390-6ndgin5tjc8oqkqn7gc16n28rmkjvjp5.apps.googleusercontent.com
   supabase secrets set --project-ref pcnwqamqdnsadranufjv GOOGLE_WEB_CLIENT_SECRET='…'
   supabase secrets set --project-ref pcnwqamqdnsadranufjv FLUCK_STATE_KEY='…'
   supabase secrets set --project-ref pcnwqamqdnsadranufjv FLUCK_USER_ID='<boss-user-uuid>'
   ```

3. Deploy:

   ```sh
   supabase functions deploy fluck-oauth --project-ref pcnwqamqdnsadranufjv
   ```

4. Check it:

   ```sh
   curl -s https://pcnwqamqdnsadranufjv.functions.supabase.co/fluck-oauth/health
   # {"ok":true,"configured":{"clientId":true,"clientSecret":true,"stateKey":true}}
   ```

## The state format is a shared contract

`tests/fluck-oauth-state.fixture.json` is byte identical to the copy in the fluck-agent-imessage
repo under `src/test/resources/`, and both suites assert the same token. Change the header bytes,
the claim order or the key derivation in one place and the other suite goes red, which is the point:
the alternative is a deployment that looks clean and refuses every callback.

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
