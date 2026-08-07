bucket         = "matchmaker-terraform-state"
key            = "dev/api.tfstate"
region         = "us-east-1"
dynamodb_table = "matchmaker-terraform-locks"
encrypt        = true
