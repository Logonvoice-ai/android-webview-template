# Setting this repo up

## 1. Two secrets

Repo → Settings → Secrets and variables → Actions → New repository secret:

| Name | Value |
|---|---|
| `BACKEND_URL` | `https://lnative.logoninvoice.com` (no trailing slash) |
| `BACKEND_API_TOKEN` | the exact `ci_token` from your `config.php` |

Both are required. The workflow now refuses to build a release without them,
rather than quietly signing with a throwaway key.

## 2. Point LNative at this repo

In `config.php`:

    'github' => [
        'token' => 'your new fine-grained PAT',
        'owner' => 'your-account-or-org',
        'repo'  => 'this-repo-name',
    ],

## 3. The token's permissions

Fine-grained PAT, scoped to this repository only:

- **Contents** — Read and write  (needed to trigger `repository_dispatch`)
- **Actions** — Read and write   (needed to list runs and download artifacts)
- **Metadata** — Read (added automatically)

Nothing else. Do not use a classic token with `repo` scope: it can reach
every repository you own.

## 4. Your signing keys are not affected

Keystores live in your own database and `storage/keystores/`, encrypted with
`APP_KEY`. Moving to a different GitHub repo does not touch them, and apps
already published stay updatable.

## 5. Before deleting the old repo

Artifacts from past builds live in the OLD repo. Open `/doctor.php` on your
site and fetch back anything still listed as missing first. Once this repo
starts building, new artifacts land here instead.
