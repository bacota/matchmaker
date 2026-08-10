# Matchmaker infrastructure

The matchmaker API, running as a Java Lambda behind an API Gateway HTTP API.

## Layout

```
main.tf                             one root for every environment; names none of them
variables.tf                        every input, with validation
modules/api                         the Lambda, the HTTP API, Cognito, and their IAM roles
modules/ui                          the S3 bucket and CloudFront distribution serving the UI
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

## The UI

`modules/ui` builds a private S3 bucket behind a CloudFront distribution and uploads four files:
`index.html`, `app.css`, `main.js`, and a `config.js` that terraform generates.

Three things about it are deliberate:

- **CloudFront, not an S3 website endpoint.** Website endpoints serve plain http, and
  `crypto.subtle` — which PKCE needs to hash the code verifier — does not exist outside a secure
  context. Hosted login would work locally and fail on the deployed site.
- **`config.js` is generated**, from `api_endpoint`, `hosted_login_url` and `user_pool_client_id`
  as the API module actually produced them. There is no step where three values are copied into a
  file by hand, and so no way for them to go stale. None of them are secret.
- **The distribution's own URL is added to `callback_urls` and `cors_allowed_origins`
  automatically**, so the site can sign in the moment it exists. The variables of the same name
  add to it — localhost for dev, a custom domain for prod.

The two modules reference each other. That is not a cycle: the bucket and distribution are created
first, the Cognito client then takes the distribution's URL as a callback, and `config.js` is
written last with that client's id.

Build the JavaScript before applying — `filemd5` on a missing file fails the plan:

```sh
mill matchmaker.ui.fullLinkJS   # not fastLinkJS: several times smaller, and minified
./tf.sh dev apply
./tf.sh dev output -raw ui_url
```

Objects are uploaded with `source_hash`, so a rebuilt `main.js` shows up as a change the same way
a rebuilt jar does. They are cached for 60 seconds and `config.js` not at all, so a redeploy is
visible without an invalidation; if you need one anyway, `ui_distribution_id` is an output.

### The bucket name

`ui_bucket_name` names the bucket. S3 names are global rather than per-account, so
`matchmaker-<env>-ui` is not guaranteed to be free; leaving the variable empty falls back to that
name. It belongs in `environments/<env>.tfvars` with the other account facts, because changing it
on a live deployment **replaces the bucket** — terraform creates the new one, uploads the four
objects, repoints the distribution and destroys the old one.

### A custom domain

Three variables, set together in `environments/<env>.tfvars`: `ui_domain_name`, `hosted_zone_id`
and `ui_certificate_arn`. Leave them empty — the usual choice for dev — and the site keeps its
generated `*.cloudfront.net` name, with nothing written to Route 53 and no certificate needed. Set
one but not the others and the plan fails on a precondition rather than half-configuring the
distribution.

With them set, an apply:

1. Puts the domain on the distribution as an `alias`, served with `ui_certificate_arn` under
   `sni-only` and a TLSv1.2 floor.
2. Points `A` and `AAAA` alias records at the distribution. Both families, because the distribution
   answers on IPv6 and an `A` record alone leaves IPv6-only clients unable to resolve the site.
   Alias rather than CNAME: a CNAME cannot sit at a zone apex, and aliases are not billed per query.

**The certificate and the zone are referenced, never created.** Both normally outlive this stack
and are shared beyond it — a certificate usually fronts more than one distribution, and a zone
holds records this configuration knows nothing about — so issuing them here would also mean being
able to destroy them. This is the same treatment `db_secret_name` gets.

Two things about the certificate are enforced by CloudFront rather than by preference:

- **It must be in us-east-1**, whatever `region` the rest of the stack runs in. That is the only
  region CloudFront reads certificates from. The module checks the region straight off the ARN,
  because otherwise this surfaces at apply time as `InvalidViewerCertificate`, which does not
  mention the region.
- **It must already be ISSUED.** A certificate still pending DNS validation is rejected, and the
  error names the ARN rather than the reason. Validate it before applying:

  ```sh
  aws acm describe-certificate --region us-east-1 \
    --certificate-arn "$ui_certificate_arn" --query 'Certificate.[Status,DomainName]'
  ```

The `url` and `origin` outputs follow the domain, and those are what `callback_urls` and
`cors_allowed_origins` are built from. So adding a domain also moves the user pool's callbacks and
the API's CORS origins onto it in the same apply, and **the domain must not be repeated** in the
`callback_urls` / `cors_allowed_origins` variables — those are for anything *else* hosted login may
return to.

Expect an apply that adds or changes the domain to spend several minutes deploying the
distribution. `ui_distribution_domain_name` stays reachable throughout: if the site is down,
comparing it against `ui_url` separates a DNS problem from a CloudFront one.

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

Nor the two things a custom domain needs, for the same reason — they outlive this stack and are
shared beyond it, so owning them here would mean being able to destroy them:

- `hosted_zone_id` — an existing Route 53 public hosted zone. Terraform writes the UI's `A` and
  `AAAA` records into it and touches nothing else in the zone.
- `ui_certificate_arn` — an existing ACM certificate covering `ui_domain_name`, in **us-east-1**
  and already ISSUED.

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
