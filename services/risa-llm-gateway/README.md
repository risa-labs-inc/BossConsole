# RISA LLM Gateway

Codex access to RISA's GLM deployment on CoreWeave, with BOSS identity and centralized usage
attribution. The production target is GCP; Supabase remains only the identity provider already
used by BOSS.

## Public contract

- `POST https://llm.risa.inc/auth/token` exchanges a valid BOSS session for an eight-hour,
  model-scoped LiteLLM virtual key.
- `POST https://llm.risa.inc/v1/responses` is the Codex Responses endpoint.
- `GET https://llm.risa.inc/healthz` reports whether Cloud SQL and LiteLLM are ready.

There is no interactive login page on this hostname. BOSS is the login surface.

## Production shape

```text
BOSS/Supabase Auth JWT
          |
          v
llm.risa.inc -> HTTPS load balancer + Cloud Armor
          |
          v
Gateway (Cloud Run) -> Cloud SQL entitlement database
          |
          | Google service identity + per-user LiteLLM key
          v
LiteLLM (Cloud Run, authenticated) -> Cloud SQL usage database
          |
          v
CoreWeave GLM
```

Both Cloud Run services reach a private-only Cloud SQL address through direct VPC egress. This
deliberately uses Cloud Run, one small VPC, and one Cloud SQL instance. It does not require GKE, a
VPC connector, a service mesh, or a Supabase database migration.

## Security boundary

1. The gateway validates the caller with the existing Supabase Auth `/user` endpoint and public
   BOSS anon key. No Supabase service-role key is used.
2. The email must be confirmed and have the exact `risalabs.ai` domain.
3. During the pilot, the normalized email must also appear in `RISA_LLM_ALLOWED_EMAILS`.
4. Cloud SQL auto-provisions the first allowed request. An existing disabled row remains disabled
   across login, passkey, and token refresh.
5. LiteLLM issues a short-lived key restricted to `coreweave-glm-5-2` and tags usage with the
   stable Supabase user ID.
6. The gateway authenticates to LiteLLM with its Google service account. LiteLLM grants Cloud Run
   invocation only to that account.
7. The CoreWeave credential is readable only by the LiteLLM service account.

The gateway must be internet-reachable because Codex runs on employee laptops, but its sensitive
endpoints are not anonymous. Cloud Run ingress accepts the public load balancer, and Cloud Armor
provides a coarse abuse ceiling. Per-user RPM/TPM controls remain in LiteLLM.

Do not enable prompt, request-body, Authorization-header, or source-code logging at the gateway,
load balancer, LiteLLM callbacks, or Cloud Logging sinks.

## Local development

Copy `.env.example` to the ignored `.env` and fill in test values:

```bash
cd services/risa-llm-gateway
cp .env.example .env
docker compose up --build
curl http://127.0.0.1:8080/healthz
```

Local Docker and production both use separate gateway and LiteLLM databases in one PostgreSQL
instance. Keeping LiteLLM's schema isolated prevents its Prisma migrations from treating the
gateway entitlement table as an unexpected pre-existing schema.

Run unit tests without containers:

```bash
PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=. python3 -m unittest discover -s tests -v
```

## GCP prerequisites

The intended deployment target is the dedicated `risa-coreweave-llm-gateway` project in
`us-central1`.
The operator needs permission to manage Cloud Run, Cloud SQL, IAM, Secret Manager, and load
balancing. DNS for `risa.inc` is managed separately in Porkbun. Terraform state must use an
access-controlled remote backend before a production apply
because it contains generated database credentials.

Build immutable gateway and LiteLLM images in an existing Artifact Registry repository:

```bash
gcloud builds submit \
  --project=PROJECT \
  --config=cloudbuild.yaml \
  --substitutions=_IMAGE_TAG=COMMIT_SHA \
  .
```

Use a commit SHA or another immutable release identifier for `_IMAGE_TAG`; do not deploy `latest`.

Create the one externally supplied production secret before Terraform runs:

```bash
gcloud secrets create risa-llm-coreweave-token \
  --project=PROJECT \
  --replication-policy=automatic
gcloud secrets versions add risa-llm-coreweave-token \
  --project=PROJECT \
  --data-file=-
```

The second command reads the token from standard input. Do not put it in shell history, a tfvars
file, CI output, BOSS, Codex configuration, or a container image.

## Terraform deployment

The configuration in `infra/gcp` creates:

- a dedicated VPC, direct Cloud Run VPC egress, and private service networking;
- one private-only PostgreSQL 16 Cloud SQL instance with separate gateway and LiteLLM databases;
- generated database credentials and LiteLLM master key in Secret Manager;
- separate least-privilege Cloud Run service accounts;
- authenticated gateway-to-LiteLLM invocation using Google identity tokens;
- the public gateway Cloud Run service;
- an external HTTPS load balancer, managed certificate, static IP, and Cloud Armor policy.

```bash
cd infra/gcp
cp terraform.tfvars.example terraform.tfvars
# Fill project, region, immutable image names, public Supabase anon key,
# CoreWeave endpoint, served model, and pilot email allowlist.
terraform init \
  -backend-config="bucket=YOUR_EXISTING_TERRAFORM_STATE_BUCKET" \
  -backend-config="prefix=risa-llm-gateway"
terraform plan
terraform apply
```

In Porkbun, create an `A` record with host `llm` and value from
`terraform output gateway_ip_address`. Certificate provisioning completes automatically after
public DNS resolves to the load balancer.

The initial `db-f1-micro` tier is for a small pilot. Review database CPU, memory, connections, and
LiteLLM latency before broad rollout, then move to a dedicated-core tier if required.

## Access administration

Disable an employee without modifying Supabase:

```sql
UPDATE llm_entitlements
SET enabled = false,
    source = 'admin_revoked',
    revoked_at = now(),
    updated_at = now()
WHERE user_id = '<SUPABASE_USER_ID>';
```

The disabled row is authoritative and cannot be re-enabled by logging in again. Existing virtual
keys expire naturally; for urgent revocation, also revoke that user's active LiteLLM keys.

LiteLLM records usage against the stable `user_id` attached during `/key/generate`. Build initial
reporting from LiteLLM's database or supported spend endpoints; export aggregates to BigQuery only
when the organization actually needs cross-system reporting.

## Credential rotation

Create a new CoreWeave token, add it as a new Secret Manager version, deploy or restart LiteLLM,
run an authenticated Codex smoke test, and only then revoke the old token. Employees take no action.

## BOSS/Codex integration

The `RISA Codex GLM` BOSS plugin writes a secret-free `risa-glm` profile, model catalog, and
`~/.local/bin/codex-glm` launcher. Codex invokes the packaged BOSS executable with `llm-token`;
that small native helper asks the already-running BOSS process over its owner-only local IPC
channel. The running, signed-in process exchanges its in-memory session for a short-lived virtual
key. BOSS must therefore be open and signed in while Codex GLM is in use.

The default profile uses `workspace-write` with `on-request` approval. It never enables Codex's
dangerous sandbox bypass.
