#!/usr/bin/env bash
set -Eeuo pipefail

SOURCE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
command -v git >/dev/null 2>&1 || { echo "Install git first: pkg install git" >&2; exit 2; }
command -v gh  >/dev/null 2>&1 || { echo "Install GitHub CLI first: pkg install gh" >&2; exit 2; }

gh auth status >/dev/null 2>&1 || gh auth login --hostname github.com --git-protocol https --web
GH_USER="$(gh api user --jq .login)"
REPO="${GH_USER}/novo-player-warp"
WORK_DIR="$(mktemp -d "${HOME}/novo-player-warp.XXXXXX")"
trap 'rm -rf "$WORK_DIR"' EXIT

if gh repo view "$REPO" >/dev/null 2>&1; then
  echo "Updating existing repository: $REPO"
  gh repo clone "$REPO" "$WORK_DIR"
else
  echo "Creating private repository: $REPO"
  git -C "$WORK_DIR" init --initial-branch=main
fi

# Copy from Android shared storage into Termux's private home before Git runs.
shopt -s dotglob nullglob
for item in "$SOURCE_DIR"/*; do
  name="${item##*/}"
  case "$name" in
    .git|.gradle|output|.build-work) continue ;;
  esac
  cp -a "$item" "$WORK_DIR/"
done

git -C "$WORK_DIR" config user.name "$GH_USER"
git -C "$WORK_DIR" config user.email "${GH_USER}@users.noreply.github.com"
git -C "$WORK_DIR" add -A
if ! git -C "$WORK_DIR" diff --cached --quiet; then
  git -C "$WORK_DIR" commit -m "Fix MASQUE dependency download and VPN first-run flow"
else
  echo "No source changes to upload."
fi

if git -C "$WORK_DIR" remote get-url origin >/dev/null 2>&1; then
  git -C "$WORK_DIR" push origin HEAD:main
else
  gh repo create "$REPO" --private --source "$WORK_DIR" --remote origin --push
fi

echo
echo "Uploaded successfully: https://github.com/${REPO}"
echo "Build workflow: https://github.com/${REPO}/actions/workflows/build-warp.yml"
echo "The push should start the build automatically."
