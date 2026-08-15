# Matchmaker infrastructure

The matchmaker API, running as a Java Lambda behind an API Gateway HTTP API.

## Layout

```
main.tf                             one root for every environment; names none of them
variables.tf                        every input, with validation
modules/api                         the Lambda, the HTTP API, Cognito, and their IAM roles
modules/ui                          the S3 bucket and CloudFront distribution serving the UI
environments/<env>.settings.tfvars  policy: memory, retention, security, session length (committed)
environments/<env>.tfvars           account facts: endpoints, subnets, user names (committed)
environments/<env>.secrets.tfvars   credentials, and nothing else (gitignored)
environments/<env>.backend.hcl      which state this environment uses
tf.sh                               run terraform against one environment
```

**One configuration, not one per environment.** Environments differ only in values, and the values
split three ways, layered by `tf.sh` in this order — later files win:

1. **Policy** — memory, log retention, advanced security, session length, SnapStart. Decisions, in
   `environments/<env>.settings.tfvars`. `diff` those two files to see exactly how prod differs
   from dev.
2. **Account facts** — the database endpoint, `db_user`, subnets, security groups, URLs. What already exists in AWS that this stack attaches to, in `environments/<env>.tfvars`.
3. **Credentials** — `db_password`, and `admin_initial_password` if you set one, in
   `environments/<env>.secrets.tfvars`.

The first two are **committed**, so any change to what gets deployed shows up in a diff and can be
reviewed. Only the third is gitignored, matched by `*.secrets.tfvars`, and it should hold nothing
but secrets — keeping it minimal means there is never a reason to make an exception to that rule.

A fresh clone therefore needs exactly one file written by hand:

```sh
cp environments/dev.secrets.tfvars.example environments/dev.secrets.tfvars
$EDITOR environments/dev.secrets.tfvars
```

`tf.sh` refuses to run without it, naming the file and the copy command rather than letting
terraform fail later with "No value for required variable".

The four policy variables have **no defaults**. A run that does not supply them fails with "No
value for required variable" rather than quietly inheriting something: prod with dev's log
retention loses its audit trail, and prod with advanced security off loses compromised-credential
blocking. Neither should be reachable by forgetting a flag.

Every resource is named `matchmaker-${environment}-...`, so both environments can live in one
account, and each has its own state key so neither can be applied over the other.

### State and locking

State lives in S3, one key per environment, configured in `environments/<env>.backend.hcl` and
passed at init time by `tf.sh`.

Locking uses **`use_lockfile = true`**, which takes a `.tflock` object in the same bucket beside
the state file. No DynamoDB table: S3 gained conditional writes, which is all a lock needs, so the
table was a second piece of infrastructure to create, pay for and keep in step with the bucket.
`dynamodb_table` is deprecated in favour of this, and terraform warns if you set it.

This needs **Terraform 1.10 or newer**, which is why the root `required_version` is `>= 1.10` while
the modules stay at `>= 1.5` — nothing in them needs anything newer.

Two things the bucket wants, neither created here:

- **Versioning**, so a corrupted or truncated state can be rolled back to the previous object.
- **`s3:DeleteObject`** in whatever policy governs the deploying principal, on top of Get and Put:
  releasing a lock deletes the `.tflock` object. Without it every run acquires a lock it can never
  release, and the next one blocks.

If a run is killed mid-apply the lock object survives; `terraform force-unlock <id>` clears it,
with the usual caveat that you must be sure nothing else is really running.

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

Add four files under `environments/`: `<env>.settings.tfvars`, `<env>.tfvars`,
`<env>.secrets.tfvars` and `<env>.backend.hcl`. Then `./tf.sh <env> apply`. **No terraform is
edited** — `main.tf` names no
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
a rebuilt jar does.

**The distribution does not cache.** It uses the AWS-managed CachingDisabled policy, so every
request is forwarded to S3 and an upload is live immediately — no invalidation step, and no window
where `index.html` and `main.js` are served from different builds. The cost is one S3 GET per
request instead of one per TTL, which is the right trade for four small files. If this ever fronts
something read-heavy, switch `cache_policy_id` to CachingOptimized
(`658327ea-f89d-4fab-a63d-7e88639e58f6`) and invalidate on deploy; `ui_distribution_id` is an
output for exactly that.

Browsers do not cache it either: all four objects are uploaded with `Cache-Control: no-store`,
which CloudFront passes through. So a redeploy is what every visitor gets on their next request,
including one with the page already open. That also removes the hazard of caching these files
independently — a browser pairing a fresh `index.html` with a `main.js` from the previous build,
which fails in ways that look like application bugs.

`no-store` rather than `no-cache`: `no-cache` still permits storing the response and revalidating
it, which leaves a copy on disk that can be served if revalidation fails.

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
able to destroy them.

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

## The hosted login domain

Cognito serves sign-in from `https://<prefix>.auth.<region>.amazoncognito.com`, and that prefix has
to be unique across **every AWS account**, not just yours. So it cannot simply be the pool name —
`matchmaker-dev` is exactly the sort of name someone else has already claimed.

Rather than making you guess a free name and re-run the apply until one sticks,
`hosted_login_domain_prefix` defaults to empty and the module derives:

```
matchmaker-<environment>-<8 hex characters of sha256(account id + region)>
```

for example `matchmaker-dev-ea602e3a`. That is unique in practice, and deterministic — the same
account and environment always produce the same prefix, so the URL players sign in at does not move
between applies. At most 40 characters, given the 20-character cap on `environment`, against
Cognito's limit of 63.

The account id is **hashed rather than used directly**: this hostname is public, appearing in every
authorize URL, and there is no reason to publish an AWS account number.

Set the variable to override it — a pool already living under another prefix, or a name chosen for
how it reads to users. Changing it on a live environment moves the sign-in URL and invalidates
every callback registered with an identity provider.

## Cold starts: SnapStart

`lambda_snap_start` (default true, per environment) snapshots the initialized JVM at publish time,
so a cold start resumes that image instead of booting a JVM and loading classes again. It is the
largest cold-start win available to a JVM Lambda.

It only applies to **published versions**, never `$LATEST`. So the function sets `publish = true`,
an alias named `live` follows each published version, and the API Gateway integration and the
`lambda:InvokeFunction` permission are both **qualified to that alias**. Pointing the gateway at
the unqualified function would silently opt out of SnapStart while still paying to publish; leaving
the permission unqualified would fail every request with `AccessDeniedException` in the gateway's
access log and nothing at all in the function's. The alias earns its keep separately, too — a bad
deploy can be rolled back by moving it to the previous version without touching the API.

Both are unconditional, so turning `lambda_snap_start` off removes the snapshot without rearranging
how the gateway reaches the function.

### What must not be in the snapshot

A snapshot is restored into *many* execution environments, so anything captured in it is shared by
all of them. That rules out open sockets, credentials, and anything that has to be unique.

This is safe today by construction rather than by luck: `Handler.services` is a `lazy val`, and the
Lambda runtime only *constructs* the handler during init — it does not invoke it. The database
pool is therefore built on the first request, after restore. The snapshot holds an initialized JVM
and loaded classes, and nothing else.

The corollary is that the pool is still built on the first request, so the restore is partial. Moving them into init would complete it, but only alongside `org.crac`
checkpoint/restore hooks that tear the pool down before the snapshot and rebuild it after —
without them every restored environment would come up holding the same dead TCP connections.
**Do not make `services` eager without adding those hooks.**

Expect an apply that changes the jar to spend an extra minute or two here: AWS runs the init phase
and takes the snapshot before the new version becomes usable.

## What this does not create

The database and its credentials are managed elsewhere and referenced by variable:

- `rds_endpoint` / `db_name` — the Aurora cluster.
- `db_user` / `db_password` — the credentials, passed straight through to the function as
  environment variables. See below.
- `subnet_ids` / `security_group_ids` — the network. The Lambda is VPC-attached, because Aurora
  is not publicly reachable. The database's security group must accept traffic from the security
  groups given here.

### The database password

`db_password` reaches the function as the `DB_PASSWORD` environment variable, alongside `DB_HOST`,
`DB_PORT`, `DB_NAME` and `DB_USER`. `Handler.dbConfig` reads all five and builds the `DbConfig`
directly — the function makes no AWS call and carries no AWS dependency beyond the Lambda runtime
interface.

It is set in `environments/<env>.secrets.tfvars`, which is gitignored, and it is the only value
in this configuration that is. The variable is also marked `sensitive`, which redacts it from plan
and apply output.

**Neither of those makes the value private.** Two places still hold it in plaintext:

- **The terraform state.** Anyone who can read the state bucket can read the password. Restrict
  that bucket to whoever is allowed to deploy.
- **The Lambda's own configuration.** Any principal with `lambda:GetFunction` can read it back,
  and it appears in the console's environment-variables panel. That is a wider audience than
  `secretsmanager:GetSecretValue` on a single secret would have been.

Rotating means editing `<env>.secrets.tfvars` and running an apply, which publishes a new function
version.

If that trade stops being acceptable, the alternative is Secrets Manager plus the AWS Parameters
and Secrets Lambda Extension: terraform grants `secretsmanager:GetSecretValue` scoped to one
secret, and the function fetches the value at runtime over `localhost:2773`, so the credential
never enters the state or the function's configuration. That is what this configuration did
previously; `git log` has the working version.

Nor the two things a custom domain needs, for the same reason — they outlive this stack and are
shared beyond it, so owning them here would mean being able to destroy them:

- `hosted_zone_id` — an existing Route 53 public hosted zone. Terraform writes the UI's `A` and
  `AAAA` records into it and touches nothing else in the zone.
- `ui_certificate_arn` — an existing ACM certificate covering `ui_domain_name`, in **us-east-1**
  and already ISSUED.

## Deploying

`./deploy.sh <env>` from the repository root does the whole thing:

```sh
./deploy.sh dev
./deploy.sh prod --yes           # no confirmation prompt
./deploy.sh dev --skip-migrate   # schema already current
./deploy.sh dev --skip-build     # artifacts already built
```

in this order, which is not arbitrary:

1. `mill __.compile` — everything, tests included, so a broken build stops the deploy before it
   touches anything.
2. `matchmaker.api.assembly` and `matchmaker.ui.fullLinkJS` — the two artifacts terraform uploads.
3. **Flyway**, so the schema is ready before code that expects it goes live. Connection details are
   read from `<env>.tfvars` and `<env>.secrets.tfvars`, the same files terraform uses.
4. `terraform plan -out=<file>`, a confirmation prompt, then **apply of that saved plan** — so what
   is applied is exactly what was displayed. A bare `apply` would compute a second plan, which can
   differ from the one you reviewed.

Two things to know:

- **Flyway needs a route to the database**, which is not publicly reachable — it is in the VPC the
  Lambda attaches to. This step works from a VPN, a bastion tunnel, or a runner inside the VPC, and
  not from an arbitrary laptop. `--skip-migrate` when the schema is already current.
- **Migrating before applying is the safe order only for additive migrations.** A migration that
  drops or renames something breaks the deployed function the moment it runs, before the new code
  is live. Expand in one deploy, contract in a later one.

The steps below are the same thing by hand.

### By hand

```sh
# 1. Build the jar the Lambda runs.
mill matchmaker.api.assembly

# 2. Supply the one file that is not in the repository, and point the backend at your state.
cd terraform
cp environments/dev.secrets.tfvars.example environments/dev.secrets.tfvars  # the db password
$EDITOR environments/dev.backend.hcl                                        # the state bucket

# Account facts (dev.tfvars) and policy (dev.settings.tfvars) are already committed; edit them
# if your endpoint, subnets or security groups differ.

# 3. Apply. tf.sh handles init, the backend, and all three var files.
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
2. The user signs in or signs up there, with a password **or a one-time code emailed to them**.
   **Neither is ever typed into this application** — that is the point of hosted login.
3. Cognito redirects back to one of `callback_urls` with an authorization code.
4. The browser exchanges the code at `${hosted_login_url}/oauth2/token`, sending the PKCE
   `code_verifier`, and gets an ID token back.
5. Every API call carries `Authorization: Bearer <id token>`.

### PKCE

The app client is created with `generate_secret = false`, making it a *public* client. Cognito
requires PKCE for the authorization code grant on public clients, so there is no separate switch:
PKCE is enabled by that line and a caller cannot opt out of it. The implicit grant is not among
`allowed_oauth_flows`, so there is no flow available that skips it.

### Passwordless sign-in by email

The pool's `sign_in_policy.allowed_first_auth_factors` is `["PASSWORD", "EMAIL_OTP"]`, so a player
can sign in either by typing a password or by having Cognito mail them a one-time code. It is
*added* to passwords rather than replacing them: existing players keep working, and a player who
never sets a password never has to invent one. Email is already the sign-in identifier and is
already in `auto_verified_attributes`, so the code goes to an address Cognito has confirmed.

Three other things have to line up, and all three are easy to miss:

- **`ALLOW_USER_AUTH` in the client's `explicit_auth_flows`.** This is the choice-based flow, where
  the client asks which factors are available and the player picks. Without it the pool accepts
  `EMAIL_OTP` and nothing ever offers it.
- **`managed_login_version = 2` on the domain.** The classic hosted UI has no passwordless support
  at all, so this is required rather than cosmetic — but it does change how the sign-in pages look.
- **An `aws_cognito_managed_login_branding` record**, or managed login will not render. Cognito's
  own defaults are used (`use_cognito_provided_values = true`); swap in a `settings` document to
  theme it.

Nothing changes in the application. The browser still goes to `/login`, still gets an authorization
code back, and still exchanges it with PKCE — which factor the player chose is entirely Cognito's
business, and the ID token that comes back is the same either way.

### Who the mail comes from

`cognito_sender_email` sets the address. Leave it empty — the dev default — and the pool uses
Cognito's built-in sender, which is capped at **50 emails a day** across the whole pool and sends
from `no-reply@verificationemail.com`. That was tolerable when email only carried sign-up
verification. Now that a player can sign in with an emailed code, an environment with real players
should set it:

```hcl
cognito_sender_email = "no-reply@matchmaker.example.com"
```

which switches the pool to SES (`email_sending_account = "DEVELOPER"`). The **SES identity ARN is
derived** from the address rather than asked for separately — an email identity is always
`arn:<partition>:ses:<region>:<account>:identity/<address>`, and the module already knows all
three parts.

Two prerequisites that terraform cannot check, and that fail at apply rather than at plan:

- **The address must be a verified SES identity** in this account and region. If you verified the
  *domain* instead of the individual address, the derived ARN does not exist and the apply fails
  naming it — say so and the ARN can be made an override.
- **The account must be out of the SES sandbox**, or the pool can only mail addresses that have
  themselves been verified, which defeats the point for sign-up.

The value must be a bare address, not `Name <addr@example.com>`, because the ARN is derived from
it; a `validation` block rejects the display-name form up front.

### The first administrator

Setting `cognito_sender_email` also creates **one admin user** in the pool, signing in with that
same address. Without it a new environment has no way in: every route needs a signed-in caller, and
creating a game needs an *admin* player, so the first administrator would have to sign up through
hosted login and then be granted admin by hand in the database.

The address is the sender's on purpose — it is already a verified SES identity, so Cognito's
invitation reaches it even in the SES sandbox, which no other address is guaranteed to do.

How that account gets its first password is `admin_initial_password`'s decision:

- **Left empty**, Cognito generates a temporary password and mails it. Nothing secret enters the
  configuration or the state. The first sign-in is the new-password challenge, and the account is
  `CONFIRMED` after it. Prefer this whenever the address can actually receive mail.
- **Set**, that value is installed as a *permanent* password and the account is `CONFIRMED`
  immediately, so you can sign in without waiting on an email. The invitation is suppressed, since
  it would quote a temporary password that is not the one to use. It goes in
  `environments/<env>.secrets.tfvars` next to `db_password`, and like `db_password` it is
  **plaintext in the terraform state** — a bootstrap credential, not a permanent one. Sign in,
  change it in managed login, and the value here stops being live. Changing the variable later
  resets the password again.

Either way, once the account is `CONFIRMED` it can use `EMAIL_OTP` like anyone else.

An administrator is two things — a Cognito user *and* a player row with `is_admin` — and terraform
can only make the first. The player table lives in the VPC and has no terraform resource, so
`deploy.sh` finishes the job: after the apply it reads the `admin_external_id` output (the user's
`sub`) and runs `SeedAdmin`, which inserts the row, or grants admin to a row that already carries
that `sub`. It runs on every deploy and does nothing when the row is already correct.

Two consequences worth knowing:

- The seeding step needs a route into the VPC, exactly like the Flyway step, and `--skip-migrate`
  skips both.
- The Cognito user has `prevent_destroy`. Recreating it issues a new `sub`, and the player row is
  keyed by the old one — the account would come back with no player and no way to grant itself
  admin again.

`ADMIN_NICKNAME` (default `admin`) names the row when it is created; an existing player keeps the
nickname it already has.

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
