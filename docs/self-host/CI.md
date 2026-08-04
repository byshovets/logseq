# Self-host image CI and supply-chain setup

The workflow is [`.github/workflows/build-self-host.yml`](../../.github/workflows/build-self-host.yml).
It builds the fork checkout with full Git history and `SELF_HOST=true`, then:

1. builds only `linux/amd64` from `scripts/self-host/Dockerfile`;
2. runs the runtime image as `node` with a read-only root and 64 MB `/tmp` tmpfs;
3. checks `/health` and runs `scripts/self-host/smoke-test.js` through the image's
   adapter, including initial upload, A-to-B synchronization, and live WebSocket
   synchronization;
4. blocks publication on fixable high/critical runtime vulnerabilities;
5. creates an SPDX JSON SBOM;
6. pushes `ghcr.io/byshovets/logseq:sha-<full commit>` (no `latest`);
7. records the repository digest, signs it with GitHub Actions OIDC, and attaches
   signed SBOM and SLSA provenance attestations;
8. uploads the digest/SBOM/provenance record for seven days and writes the exact
   `image@sha256:...` deployment reference into the job summary.

All third-party actions are pinned to commits. Both Docker bases are pinned to
immutable index digests. The human-readable base tags remain beside the digests
so Renovate/Dependabot or a manual review can identify intended upgrades.

## Enable it on GitHub

1. Push this branch and workflow to `github.com/byshovets/logseq`. The automatic
   trigger is currently `byshovets/self-host`; change `on.push.branches` if the
   production branch changes.
2. In **Settings -> Actions -> General**, enable Actions and allow the actions
   used by the workflow. No personal publish token is needed: the job grants its
   short-lived `GITHUB_TOKEN` `packages: write` and grants `id-token: write` only
   for keyless signing.
3. Open **Actions -> Build self-host image -> Run workflow** for the first run.
   A push to the configured branch also runs it. Full builds are serialized so
   cancellation cannot leave a push half-signed.
4. Read the final job summary and copy only
   `ghcr.io/byshovets/logseq@sha256:...` into Ansible.
5. The first GHCR package is private by default. Recommended: open the package's
   **Package settings -> Change visibility -> Public**. This is irreversible.
   Public GHCR pulls need no server credential and the image contains application
   code only; notebook data is mounted later at `/data` and is never part of the
   build context or published layers.

The package is linked to the source repository by its
`org.opencontainers.image.source` label. If the workflow cannot push, give the
repository Actions access under the package's settings and confirm the repository
permits read/write workflow tokens.

## GitHub Free limits checked 2026-08-04

- Standard GitHub-hosted runners are free and unlimited for public repositories.
  A public `ubuntu-24.04` runner has 4 vCPU, 16 GB RAM, and 14 GB SSD. A private
  GitHub Free repository gets 2 vCPU, 8 GB RAM, the same 14 GB SSD, and consumes
  the account's 2,000 included minutes/month. See [GitHub-hosted runner
  specifications](https://docs.github.com/en/actions/reference/runners/github-hosted-runners)
  and [Actions billing](https://docs.github.com/en/billing/concepts/product-billing/github-actions).
- GitHub-hosted jobs have a six-hour maximum. This workflow sets a four-hour
  timeout. It deletes unrelated Android/.NET/Haskell/CodeQL SDKs at the start
  because the multi-stage Clojure/Node build is more likely to hit disk than RAM.
- GitHub Free includes 500 MB Actions artifact storage and a separate 10 GB cache
  per repository. This workflow keeps only three small records for seven days
  and exports the minimum BuildKit cache. See [Actions limits](https://docs.github.com/en/actions/reference/limits).
- GitHub currently states that Container registry image storage and bandwidth are
  free, while reserving the right to announce a policy change. Public packages
  are also free and anonymously pullable. See [GitHub Packages
  billing](https://docs.github.com/en/billing/concepts/product-billing/github-packages)
  and [GHCR access/visibility](https://docs.github.com/en/packages/learn-github-packages/configuring-a-packages-access-control-and-visibility).
- GitHub-native artifact attestations on Free/Pro/Team require a public source
  repository. The workflow deliberately uses OCI-native Cosign signatures and
  attestations, so it still produces signed provenance when the source repository
  is private. See [GitHub artifact-attestation availability](https://docs.github.com/en/actions/how-tos/secure-your-work/use-artifact-attestations/use-artifact-attestations).

For a no-payment account, making the source repository and GHCR package public is
the practical choice: faster runner, unlimited minutes, anonymous deploy pulls,
and no notebook-data exposure. If the source must remain private, budget roughly
by measured workflow duration; GitHub stops hosted-runner use when the included
minutes are exhausted and no payment method is configured.

Do not register LEXX as the builder to avoid those limits. The multi-stage image
temporarily needs the JDK, Clojure, native build tools, the full Node workspace,
browser smoke tooling, BuildKit cache, and runtime layers. Keeping all of that on
the ephemeral GitHub runner avoids expanding LEXX's already-large rootless image
store under `/var`; LEXX pulls only the final digest-qualified runtime image.

## Verify the signature before deployment

The workflow signs the digest keylessly with Sigstore. Verify the exact workflow
identity and GitHub's OIDC issuer; never accept an arbitrary Fulcio certificate:

```bash
IMAGE='ghcr.io/byshovets/logseq@sha256:<digest>'
IDENTITY='^https://github\.com/byshovets/logseq/\.github/workflows/build-self-host\.yml@refs/heads/.+$'

cosign verify \
  --certificate-identity-regexp "$IDENTITY" \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  "$IMAGE"
cosign verify-attestation --type spdxjson \
  --certificate-identity-regexp "$IDENTITY" \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  "$IMAGE"
cosign verify-attestation --type slsaprovenance1 \
  --certificate-identity-regexp "$IDENTITY" \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  "$IMAGE"
```

Run this on the Ansible controller before changing the deployed digest. Pulling
by the verified digest then prevents the registry from substituting different
content.

Podman's `containers-policy.json` supports `sigstoreSigned`, but its Fulcio
policy currently matches an email identity while GitHub Actions certificates use
a workflow URI identity. Do not weaken policy to issuer-only trust. Keep the
existing digest pin plus the explicit `cosign verify` gate. If native Podman
enforcement is mandatory, switch CI to a dedicated Cosign key pair, distribute
only the public key through Ansible, enable `use-sigstore-attachments` for this
GHCR repository, and require that key for the exact repository scope in
`policy.json`.

## Private GHCR: rootless read-only credentials

Public is simpler. If the package remains private, create a dedicated GitHub
machine user or narrowly scoped token with package read access. GHCR's documented
CLI authentication uses a classic PAT with `read:packages`; do not give the
server `write:packages` or repository write access.

Store it only in Ansible Vault:

```bash
ansible-vault edit group_vars/lexx/vault.yml
```

```yaml
vault_ghcr_logseq_username: deploy-logseq
vault_ghcr_logseq_token: ghp_REDACTED
```

Install a persistent rootless auth file (adjust the account and home path to the
real Quadlet owner):

```yaml
- name: Create rootless container config directory
  become: true
  ansible.builtin.file:
    path: /home/logseq/.config/containers
    state: directory
    owner: logseq
    group: logseq
    mode: '0700'

- name: Authenticate rootless Logseq pulls to private GHCR
  become: true
  become_user: logseq
  no_log: true
  containers.podman.podman_login:
    registry: ghcr.io
    username: "{{ vault_ghcr_logseq_username }}"
    password: "{{ vault_ghcr_logseq_token }}"
    authfile: /home/logseq/.config/containers/auth.json
```

Point the Quadlet/pull task at that auth file and at the digest-qualified image.
Do not put the PAT in the Quadlet, environment file, command line, Git repository,
or CI artifact.
