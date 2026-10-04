# Infrastructure as code for the free-tier stack: Neon Postgres, Upstash Redis/QStash, Vercel.
# Providers and resources are added in M1; state stays local until a remote backend is chosen.
terraform {
  required_version = ">= 1.12"
}
