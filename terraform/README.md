# Matchmaker infrastructure

The matchmaker API, running as a Java Lambda behind an API Gateway HTTP API.

## Layout

```
main.tf                             one root for every environment; names none of them
variables.tf                        every input, with validation
modules/api                         the Lambda, the HTTP API, Cognito, and their IAM roles
environments/<env>.settings.tfvars  policy: memory, retention, security, session length (committed)
environments/<env>.tfvars           account facts: endpoints, subnets, secrets (gitignored)
environments/<env>.backend.hcl      which state this environment uses
tf.sh                               run terraform against one environment
```

**One configuration, not one per environment.** Environments differ only in values, and the values
split in two:

- **Policy** — memory, log retention, advanced security, session length. Decisions, so they are
  committed, in `environments/<env>.settings.tfvars`. `diff` those two files to see exactly how
  prod differs from dev.
- **Account facts** — the database endpoint, subnets, security groups, secret name, domain prefix,
  URLs. These name real infrastructure, so they live in `environments/<env>.tfvars`, which is
  gitignored.

The four policy variables have **no defaults**. A run that does not supply them fails with "No
value for required variable" rather than quietly inheriting something: prod with dev's log
retention loses its audit trail, and prod with advanced security off loses compromised-credential
blocking. Neither should be reachable by forgetting a flag.

Every resource is named `matchmaker-${environment}-...`, so both environments can live in one
account, and each has its own state key so neither can be applied over the other.

### Always use `tf.sh`

```sh
./tf.sh dev plan
./tf.sh prod apply
./tf.sh dev output -raw api_endpoint
```

A single root serving several environments has one sharp edge: terraform remembers the chosen
backend in `.terraform`, so a bare `terraform apply` after working on the other environment would
target the wrong state, with no warning and a plan that looks entirely reasonable. `tf.sh`
re-initialises the backend on every run and always passes both var files, which removes
that edge. Running `terraform` directly reintroduces it.

### Adding an environment

Add three files under `environments/`: `<env>.settings.tfvars`, `<env>.tfvars` and
`<env>.backend.hcl`. Then `./tf.sh <env> apply`. **No terraform is edited** — `main.tf` names no
environment, and the `environment` variable is validated by format rather than against a list.

## What this does not create

The database and its credentials are managed elsewhere and referenced by variable:

- `rds_endpoint` / `db_name` — the Aurora cluster.
- `db_secret_name` — an existing Secrets Manager secret holding
  `{"username": ..., "password": ...}`. Terraform reads only the secret's ARN, in order to scope
  the Lambda's `secretsmanager:GetSecretValue` grant. The credential value never enters the
  Terraform state or a plan.
- `secrets_extension_layer_arn` — the AWS Parameters and Secrets Lambda Extension layer. The
  function reads its credentials from this over `localhost` rather than bundling the AWS SDK,
  which would add roughly 8 MB (Netty and Apache HttpClient) to serve one call per cold start.
  The ARN is region- and version-specific, so AWS publishes no default worth hardcoding; find
  the current one for your region in the AWS Secrets Manager User Guide.
- `subnet_ids` / `security_group_ids` — the network. The Lambda is VPC-attached, because Aurora
  is not publicly reachable. The database's security group must accept traffic from the security
  groups given here.

## Deploying

```sh
# 1. Build the jar the Lambda runs.
mill matchmaker.api.assembly

# 2. Point terraform at your infrastructure.
cd terraform
cp environments/dev.tfvars.example environments/dev.tfvars   # then edit it
$EDITOR environments/dev.backend.hcl                         # the S3 bucket and lock table

# 3. Apply. tf.sh handles init, the backend, and the var file.
./tf.sh dev apply
```

Redeploying code is the same `assembly` then `apply`: the function's `source_code_hash` tracks
the jar, so a rebuilt jar shows up as a change to the function and nothing else.

Database migrations are separate, and run against the same database with Flyway — see the
comments in `build.mill`.

## Authentication

Cognito hosted login, with an API Gateway JWT authorizer in front of every route.

**Each environment gets its own user pool.** A dev token is meaningless in prod, and a dev sign-up
can never become a prod account. The pool, its hosted login domain, and one app client are all
created by `modules/api/cognito.tf`.

The flow:

1. The browser sends the user to the hosted UI, `${hosted_login_url}/login?...`, with a PKCE
   `code_challenge`.
2. The user signs in or signs up there. **Passwords are never typed into this application** —
   that is the point of hosted login.
3. Cognito redirects back to one of `callback_urls` with an authorization code.
4. The browser exchanges the code at `${hosted_login_url}/oauth2/token`, sending the PKCE
   `code_verifier`, and gets an ID token back.
5. Every API call carries `Authorization: Bearer <id token>`.

### PKCE

The app client is created with `generate_secret = false`, making it a *public* client. Cognito
requires PKCE for the authorization code grant on public clients, so there is no separate switch:
PKCE is enabled by that line and a caller cannot opt out of it. The implicit grant is not among
`allowed_oauth_flows`, so there is no flow available that skips it.

### ID token, not access token

The authorizer's `audience` is the app client id, which matches the `aud` claim of a Cognito **ID**
token. Cognito's access tokens carry `client_id` instead and have no `aud`, so they are rejected.
Send the ID token.

### What the application trusts

API Gateway verifies signature, expiry, issuer and audience before the function is invoked, and
answers 401 itself otherwise. The function reads the `sub` claim out of
`requestContext.authorizer.jwt.claims` and does not re-verify it — see `Authenticator.GatewayClaims`.
That is sound only because the `$default` route sets `authorization_type = "JWT"` and the only
`lambda_permission` is API Gateway's. If you add a route, it inherits the authorizer; if you ever
add one that does not, the function will answer 401 rather than admit an unidentified caller.

`AUTH_MODE=gateway` selects this, and the terraform sets it. The code defaults to `gateway` when
the variable is unset, so a terraform mistake cannot degrade the deployed function into trusting a
header.

### Locally

`LocalServer` runs with `AUTH_MODE=header`, taking the caller from `X-External-Id` on trust. It
binds to loopback and allows only loopback CORS origins; see `matchmaker/ui/README.md` for the
local stack. Verifying a real dev-pool token in-process (against the pool's public JWKS, which needs
no AWS credentials) is `Authenticator.VerifiedToken`, which is not written yet.

### After the first apply

Give the UI these outputs: `hosted_login_url`, `user_pool_client_id`, `api_endpoint`. None are
secret — the client id is a query parameter of the authorize URL. Then check the API refuses
anonymous callers:

```sh
curl -i $(./tf.sh dev output -raw api_endpoint)/me     # 401 from the gateway, before any code runs
```
