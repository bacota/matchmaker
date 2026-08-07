// State is kept remotely so that dev and prod cannot be applied over each other from different
// machines. Fill in the bucket and table, then `terraform init`.
terraform {
  backend "s3" {
    # bucket         = "matchmaker-terraform-state"
    # key            = "prod/api.tfstate"
    # region         = "us-east-1"
    # dynamodb_table = "matchmaker-terraform-locks"
    # encrypt        = true
  }
}
