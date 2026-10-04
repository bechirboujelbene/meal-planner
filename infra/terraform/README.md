# infra/terraform

Provisions the €0 stack: Neon Postgres (Frankfurt), Upstash Redis + QStash, Vercel project + env vars.
Secrets are passed via environment variables / `*.tfvars` (never committed).

```bash
terraform init
terraform plan
```
