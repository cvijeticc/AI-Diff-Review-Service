# Deployment

CI/CD runs on GitHub Actions (`.github/workflows/deploy.yml`):

1. **test** — `./mvnw verify` on every push, PR and manual dispatch.
2. **deploy** — only on `main` (or manual dispatch), only if tests pass.
   Actions SSHes into the VPS with a **forced-command key** that can do nothing
   but run `deploy.sh`, passing the tested commit SHA. The server checks out
   exactly that SHA, rebuilds the image, and health-checks it. If `/health`
   does not answer within ~2 minutes the previous commit is redeployed
   automatically and the job fails.

The service listens on `127.0.0.1:8020` (container port 8080). Public access is
expected to go through nginx + TLS, not the host port.

## Required GitHub secrets

| Secret | Value |
|---|---|
| `VPS_HOST` | server IP or hostname |
| `VPS_USER` | dedicated deploy user (e.g. `diffreview-deploy`) |
| `VPS_SSH_KEY` | **private** key of the deploy keypair (forced-command restricted) |
| `VPS_KNOWN_HOSTS` | server host key, from `ssh-keyscan -H <host>` — pins the host, no TOFU |
| `VPS_PORT` | optional, defaults to `22` |

App secrets (`AUTH_TOKEN`, `ANTHROPIC_API_KEY`) are **not** GitHub secrets. They
live only in `/srv/backend/diff-review-service/.env` on the server (0600), so a
compromised CI account cannot read them.

## One-time server setup

```bash
BASE=/srv/backend/diff-review-service
USER=diffreview-deploy

adduser --system --group --shell /bin/bash --home /home/$USER $USER
usermod -aG docker $USER            # needed to run docker compose

install -d -o $USER -g $USER "$BASE"
git clone https://github.com/cvijeticc/AI-Diff-Review-Service "$BASE/repo"
chown -R $USER:$USER "$BASE/repo"

cp "$BASE/repo/deploy/.env.example" "$BASE/.env"   # then edit: real AUTH_TOKEN
chmod 600 "$BASE/.env"

# forced-command entry point, root-owned so the deploy user cannot rewrite it
install -o root -g $USER -m 750 "$BASE/repo/deploy/deploy.sh" "$BASE/deploy.sh"

# deploy keypair; the private half becomes the VPS_SSH_KEY secret
ssh-keygen -t ed25519 -N '' -C github-actions-diffreview -f /root/.ssh/diffreview_deploy_ed25519
install -d -o $USER -g $USER -m 700 /home/$USER/.ssh
printf 'command="%s/deploy.sh",restrict %s\n' "$BASE" \
  "$(cat /root/.ssh/diffreview_deploy_ed25519.pub)" \
  > /home/$USER/.ssh/authorized_keys
chown $USER:$USER /home/$USER/.ssh/authorized_keys
chmod 600 /home/$USER/.ssh/authorized_keys
```

Manual deploy / rollback from the server:

```bash
sudo -u diffreview-deploy /srv/backend/diff-review-service/deploy.sh          # latest main
sudo -u diffreview-deploy env SSH_ORIGINAL_COMMAND=<sha> \
  /srv/backend/diff-review-service/deploy.sh                                  # specific commit
```
