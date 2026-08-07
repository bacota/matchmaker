# Matchmaker infrastructure

The matchmaker API, running as a Java Lambda behind an API Gateway HTTP API.

## Layout

```
modules/api          the Lambda, the HTTP API, and their IAM roles
environments/dev     dev instance of that module
environments/prod    prod instance of that module
```

Every resource is named `matchmaker-${environment}-...`, so both environments can live in one
account.

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
cd terraform/environments/dev
cp terraform.tfvars.example terraform.tfvars   # then edit it
$EDITOR backend.tf                             # uncomment and fill in the S3 backend

# 3. Apply.
terraform init
terraform apply
```

Redeploying code is the same `assembly` then `apply`: the function's `source_code_hash` tracks
the jar, so a rebuilt jar shows up as a change to the function and nothing else.

Database migrations are separate, and run against the same database with Flyway — see the
comments in `build.mill`.

## Authentication

There is none yet. The API takes the caller's identity from an `X-External-Id` header and trusts
it, which is safe only because nothing is deployed publicly yet. Before this is exposed to real
users it needs the Cognito hosted-login/PKCE flow described in `design.txt`, at which point the
header is replaced by a verified token — a change confined to `Router.callerOf`, plus a Cognito
user pool and a JWT authorizer here.
