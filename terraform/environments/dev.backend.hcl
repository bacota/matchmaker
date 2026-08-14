bucket = "matchmaker-terraform-state"
key    = "dev/api.tfstate"
region = "us-east-1"

# Locking is done with a .tflock object in this same bucket, next to the state file, rather than
# with a DynamoDB table. S3 gained conditional writes, which is all a lock needs, so the table was
# a second piece of infrastructure to create, pay for and keep in step with the bucket.
# `dynamodb_table` is deprecated in favour of this.
use_lockfile = true

encrypt = true
