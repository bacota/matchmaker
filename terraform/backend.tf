// State lives remotely, and each environment has its own key so one can never be applied over
// another. The block is deliberately empty: the values come from
// `environments/<env>.backend.hcl` at init time, which is what lets one configuration serve
// several environments.
//
// Never run `terraform init` here by hand — use ./tf.sh, which passes the right backend and
// reconfigures. An init that reuses the previous environment's backend is how dev gets applied
// onto prod's state.
terraform {
  backend "s3" {}
}
