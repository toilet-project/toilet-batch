# Checkpoint credential rotation

After the operator updates `ERASURE_CHECKPOINT_GITHUB_TOKEN` in both API and batch
repository secrets, use the manual `Checkpoint token rotation` workflow. Nothing is
scheduled and pull requests never receive production credentials.

1. Run `probe` against both repositories' secrets. It reads only the fixed private
   checkpoint repository and main reference; output contains expiry, never the token.
2. Run `check` with the actual running API and batch image commits. It verifies the
   installed shared maintenance lease, active runtime/config agreement, local image
   IDs, current health and candidate GitHub access. No configuration is changed.
3. Pin `CHECKPOINT_TOKEN_ROTATION_APPROVED_SHA` to the reviewed batch main commit.
   With other deployments idle, run `apply` once using the same image commits.
4. Run `verify` from both repositories. The caller's stored secret must exactly match
   both live container environments, and the installed ledger/restore checks must pass.
5. Clear the approval variable after completion. If acknowledgement is lost, inspect
   with `check`/`verify` before taking another action; do not automatically retry apply.

The batch workflow can be called by a pinned API workflow using `secrets: inherit`.
API callers may probe/check/verify, but only the batch main workflow with its exact
approval pin can apply. Caller workflows pin `tools_ref` to the reviewed batch commit.

Transport uses existing pinned SSH host keys and Cloudflare Access credentials.
The replacement PAT is passed through SSH stdin, never arguments, logs or artifacts.
Only the two existing `.account-lifecycle.env` files are atomically updated (mode 600).
Original bytes remain in process memory for rollback. Both files change before either
service is recreated, while the shared maintenance lease excludes account jobs.
Compose rendering is checked for credential-only differences before each recreation.
Images are neither rebuilt nor pulled. Same-image services are checked for health,
unchanged account flags, mounts and Docker configuration, and the checkpoint reference
must remain fixed. No membership/ledger/checkpoint data writes are performed.

If verification fails, both original configurations are restored and services recreated.
An unexpected concurrent edit is never overwritten. A terminated process/SSH connection
can leave a partial rotation; retain the new repository secret and inspect runtime state.
A revoked original token cannot become valid by restoring its former configuration.

## Regenerated tokens and startup dependencies

The API's review-unlink journal verifies `review-anonymization-v1` during startup.
Regenerating the PAT invalidates the old credential, so restoring that value can
prevent API startup. Both account-main and review references must be readable by
the candidate. Original credentials returning HTTP 401 are never rollback targets;
retain the valid replacement and inspect before proceeding.

The `recover` operation is tightly limited to a revoked original, a healthy batch,
matching dotenv/runtime configurations, the exact running images, and API startup
failure in `reviewUnlinkJournal`. It is allowed only from the batch repository at
`CHECKPOINT_TOKEN_ROTATION_APPROVED_SHA`. All other cases require investigation.
`diagnose` reads health and credential-equality booleans, and extracts only known
error classes/bean names; it does not forward raw application logs. Non-main
diagnostics require `CHECKPOINT_TOKEN_DIAGNOSTIC_SHA` to match the exact source.
Clear both temporary pins when done.

Docker bind enumeration order is normalized while preserving all specifications,
duplicates, mount permissions and every other HostConfig field. Actual mounts,
Compose configuration, image IDs and complete application environments are still
compared. Unexpected configuration differences remain a failure.
