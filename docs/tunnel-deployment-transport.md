# Cloudflare Tunnel deployment transport (pending production approval)

## Scope
This change replaces the existing deployment SSH transport with Cloudflare Access Service Auth and strict, pinned OpenSSH. The build, image publication, trigger, job configuration and remote deployment commands remain identical to the reviewed baseline verified by `scripts/verify-tunnel-transport.cjs`.

## Required configuration
- Secrets: `TUNNEL_DEPLOY_ACCESS_CLIENT_ID`, `TUNNEL_DEPLOY_ACCESS_CLIENT_SECRET`, `TUNNEL_DEPLOY_SSH_KNOWN_HOSTS`.
- Existing Secrets: `MINI_PC_KEY`, `MINI_PC_USERNAME`.
- Variable: `TUNNEL_DEPLOY_SSH_HOST`.
- Only the three named deployment tokens are allowed by the dedicated Access policy. The origin must validate the dedicated application's JWT.
- Cloudflared package version and SHA256 are pinned. Host-key validation, public-key-only authentication and forwarding restrictions must remain enabled.

## Failure behavior
SSH is bounded to 10 minutes and is not retried automatically. A timeout or broken connection does not prove the remote deployment stopped. Check the actual container/process/lock state before retrying or rolling back. Runner temporary credential and command files are removed in an always-run step.

## Before production merge
- [x] Each repository passed a short, live, read-only token/SSH/host-pin check.
- [x] [Five-minute idle connection passed](https://github.com/toilet-project/toilet-batch/actions/runs/34113703446); remote read-only checks and runner cleanup also succeeded.
- [ ] Approve and verify deployment SSH boot persistence; it is currently not boot-enabled.
- [ ] Finish the agreed token expiry alert implementation and validation.
- [ ] Review the current base and real deployment diff again.
- [ ] Obtain explicit approval for main merge and the resulting production deployment.


A five-minute read-only pass is not a guarantee for ten-minute sessions, token-expiry behavior, network outages or a real deployment. Existing recovery access stays available. No firewall, router, DNS removal or business-data mutation is part of this PR.

## Rollback
Keep the prior transport and deployment configuration available until production acceptance. Restoring the prior workflow also requires a deliberate approved change: any push to main triggers deployment. Do not delete the shared production Tunnel or revoke tokens used by other applications as a shortcut.
