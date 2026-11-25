# Agent GitHub engines

Deploy with the companion migration and updated optimist console/provisioner and Max host.
This feature branch does not deploy any service or modify production data.

Register a dedicated GitHub App. Required repository permissions: Administration write,
Contents write, Pull requests write, Workflows write, Actions read, Checks read and Metadata read.
Add organisation **Members read** permission to verify the connecting GitHub user is an active org owner.
Select **Request user authorization (OAuth) during installation**. Set the OAuth callback
URL to `<supabase>/functions/v1/agent-github/connect/callback` and use the same setup URL.
The callback requires both the single-use state and OAuth `code`; it exchanges the code
and verifies the requested installation appears in `GET /user/installations`, then requires
active admin membership of that GitHub organisation. A bare
installation ID is never accepted. See [GitHub's setup URL guidance](https://docs.github.com/en/apps/creating-github-apps/registering-a-github-app/about-the-setup-url).

Configure edge-function secrets (never in source):

- BOSS_AGENTS_GH_APP_ID
- BOSS_AGENTS_GH_APP_SLUG
- BOSS_AGENTS_GH_APP_PRIVATE_KEY (PKCS#1 or PKCS#8 PEM)
- BOSS_AGENTS_GH_APP_CLIENT_ID
- BOSS_AGENTS_GH_APP_CLIENT_SECRET
- AGENTS_CONSOLE_URL (console origin)

No webhook is required in v1. Tokens are minted per request, repository-restricted and expire
in an hour. Max receives no workflows or administration permission. Main requires a review
and CI including for admins, stock is locked, and the tool pushes only max/* branches.
Private repository protection needs a GitHub plan that supports it; setup fails closed if
GitHub refuses protection. Local test token mode never relaxes protection.

For local tests only, AGENT_GITHUB_TEST_MODE=1, AGENT_GITHUB_TEST_TOKEN,
AGENT_GITHUB_TEST_ACCOUNT, AGENT_GITHUB_TEST_ACCOUNT_TYPE and AGENT_GITHUB_PUBLIC_URL
can stand in for an App. SUPABASE_URL must be loopback or a named local-stack container.
This mode tests repository APIs, but does not prove the real App installation or OAuth flow.
Never configure it on a production deployment.

Run `deno test --config deno.json --allow-env --allow-net tests/` here and
`supabase test db supabase/tests/agent_engine_repos_test.sql` against a disposable local stack.
The workflow builds/tests the whole engine without store credentials. Import checks main,
workflow, event, successful result and embedded source commit; it bounds archive expansion
and stores by SHA-256 in a private bucket. Publishing pins before changing the live version.

Existing repository linking and automatic upstream release notifications are follow-ups.
