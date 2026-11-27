#!/usr/bin/env bash
set -Eeuo pipefail

SOURCE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
command -v git >/dev/null 2>&1 || { echo "Git is required. Install it first." >&2; exit 2; }
command -v gh >/dev/null 2>&1 || { echo "GitHub CLI (gh) is required. Install it first." >&2; exit 2; }
gh auth status >/dev/null 2>&1 || gh auth login
GH_USER="$(gh api user --jq .login)"

# Reuse the project's existing repository when available. This avoids Git's
# shared-storage limitations when the ZIP is unpacked under Android Downloads.
REPO=""
if git -C "$SOURCE_DIR" rev-parse --is-inside-work-tree >/dev/null 2>&1 \
   && git -C "$SOURCE_DIR" remote get-url origin >/dev/null 2>&1; then
  REPO="$(cd "$SOURCE_DIR" && gh repo view --json nameWithOwner --jq .nameWithOwner)"
else
  REPO_NAME="$(gh repo list "$GH_USER" --limit 100 --json name,createdAt \
    --jq '[.[] | select((.name == "novo-player-warp") or (.name | startswith("novo-player-warp-")))] | sort_by(.createdAt) | last | .name // empty')"
  if [[ -n "$REPO_NAME" ]]; then REPO="${GH_USER}/${REPO_NAME}"; fi
fi

WORK_DIR="$(mktemp -d "${HOME}/novo-player-warp.XXXXXX")"
if [[ -n "$REPO" ]]; then
  echo "Updating existing repository: $REPO"
  gh repo clone "$REPO" "$WORK_DIR"
else
  echo "No existing NOVO Player repository found; creating one."
  git -C "$WORK_DIR" init --initial-branch=main
fi

# Copy the project into Termux's private home folder, excluding any source
# .git directory so an extracted ZIP cannot overwrite the cloned repository.
shopt -s dotglob nullglob
for item in "$SOURCE_DIR"/*; do
  [[ "${item##*/}" == ".git" ]] && continue
  cp -a "$item" "$WORK_DIR/"
done

if ! git -C "$WORK_DIR" config user.name >/dev/null 2>&1; then
  git -C "$WORK_DIR" config user.name "$GH_USER"
fi
if ! git -C "$WORK_DIR" config user.email >/dev/null 2>&1; then
  git -C "$WORK_DIR" config user.email "${GH_USER}@users.noreply.github.com"
fi

git -C "$WORK_DIR" add -A
if ! git -C "$WORK_DIR" diff --cached --quiet; then
  git -C "$WORK_DIR" commit -m "Fix NOVO Player WARP build and VPN consent flow"
else
  echo "No file changes to upload."
fi

if [[ -n "$REPO" ]]; then
  git -C "$WORK_DIR" push origin HEAD:main
else
  REPO="${GH_USER}/novo-player-warp-$(date -u +%Y%m%d-%H%M%S)"
  gh repo create "$REPO" --private --source "$WORK_DIR" --remote origin --push
fi

echo
echo "Uploaded: https://github.com/${REPO}"
echo "GitHub Actions will rebuild the APK. Open Actions and download NOVO-PLAYER-MASQUE-APK when it finishes."
