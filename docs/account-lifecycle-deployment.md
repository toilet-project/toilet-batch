# Account erasure deployment preparation

Production activation is not authorized. The workflow requires repository variable
`ACCOUNT_LIFECYCLE_DEPLOYMENT_APPROVED=true` and an exact `ACCOUNT_LIFECYCLE_DEPLOYMENT_APPROVED_SHA` match are required before building/pushing an image or connecting to the host.
The current preflight forces maintenance true and retention/erasure/ledger/catalogue false; it rejects activation.

- `ACCOUNT_LIFECYCLE_MAINTENANCE=true` suppresses the scheduled post-sync job AND rejects direct worker erasure before DB/Redis/R2 access.
- Stopping configuration is startup-only. Drain/restart all API/batch writers; it does not cancel an already-running transaction.
- No new Redis instance is created. The API already keeps refresh-token and recovery-proof namespaces in Redis. The batch must clear those same namespaces during erasure, using the same host, port, database and `REDIS_PASSWORD`.
- A missing batch Redis password is allowed ONLY for this disabled preparation stage, and readiness reports it as missing. Provision the API's existing password in the batch repository before activation. Never generate a different password to fix the mismatch.
- R2 endpoint/realm/bucket/key ID are repository variables; credentials and encryption key JSON are secrets. New values travel as masked base64, then single-quoted dotenv in `.account-lifecycle.env` (0600). Encoding is not encryption and this file must not be committed.
- This batch does not run V11 migrations. The API's existing Flyway path owns the migration; manual duplicate SQL is prohibited.

The ordinary 02:00 public-data sync remains independent. Actual erasure remains disabled pending ledger/checkpoint/backup/policy/connection verification and a separate production approval.
