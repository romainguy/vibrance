#!/usr/bin/env bash
set -euo pipefail

PUSH=true

for arg in "$@"; do
  case "$arg" in
    --local|--no-push)
      PUSH=false
      ;;
    -h|--help)
      cat <<'EOF'
Usage: ./website/deploy.sh [--local | --no-push]

Builds the static website (website/build.mjs), verifies JS parity tests,
commits the generated site to the gh-pages branch via a temporary git worktree,
and pushes gh-pages to origin unless --local (or --no-push) is passed.
EOF
      exit 0
      ;;
    *)
      echo "Unknown option: $arg" >&2
      echo "Run ./website/deploy.sh --help for usage." >&2
      exit 1
      ;;
  esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
DIST_DIR="$SCRIPT_DIR/dist"

echo "==> Building and verifying website..."
node "$SCRIPT_DIR/build.mjs"

VERSION="$(awk -F'=' '/^VERSION_NAME[[:space:]]*=/ { gsub(/[[:space:]]/, "", $2); print $2; exit }' "$REPO_ROOT/gradle.properties")"
SOURCE_SHA="$(git -C "$REPO_ROOT" rev-parse --short HEAD)"

WORKTREE_DIR="$(mktemp -d "${TMPDIR:-/tmp}/vibrance-gh-pages.XXXXXX")"

cleanup() {
  git -C "$REPO_ROOT" worktree remove --force "$WORKTREE_DIR" >/dev/null 2>&1 || rm -rf "$WORKTREE_DIR"
}
trap cleanup EXIT

echo "==> Preparing gh-pages worktree..."
if git -C "$REPO_ROOT" show-ref --verify --quiet refs/heads/gh-pages; then
  git -C "$REPO_ROOT" worktree add --quiet "$WORKTREE_DIR" gh-pages
elif git -C "$REPO_ROOT" show-ref --verify --quiet refs/remotes/origin/gh-pages; then
  git -C "$REPO_ROOT" worktree add --quiet -b gh-pages "$WORKTREE_DIR" origin/gh-pages
else
  git -C "$REPO_ROOT" worktree add --quiet --detach "$WORKTREE_DIR"
  git -C "$WORKTREE_DIR" checkout --orphan gh-pages
  git -C "$WORKTREE_DIR" rm -rf --ignore-unmatch . >/dev/null 2>&1 || true
fi

if command -v rsync >/dev/null 2>&1; then
  rsync -a --delete --exclude='.git' "$DIST_DIR/" "$WORKTREE_DIR/"
else
  find "$WORKTREE_DIR" -mindepth 1 -maxdepth 1 ! -name '.git' -exec rm -rf {} +
  cp -R "$DIST_DIR/." "$WORKTREE_DIR/"
fi

git -C "$WORKTREE_DIR" add -A

if git -C "$WORKTREE_DIR" diff --cached --quiet; then
  echo "==> gh-pages is already up to date (no file changes)."
else
  COMMIT_MSG="Deploy website for v${VERSION} (${SOURCE_SHA})"
  git -C "$WORKTREE_DIR" commit -m "$COMMIT_MSG"
  echo "==> Committed to gh-pages: $COMMIT_MSG"
fi

if [ "$PUSH" = true ]; then
  echo "==> Pushing gh-pages to origin..."
  git -C "$REPO_ROOT" push origin gh-pages
  echo "==> Deployment complete."
else
  echo "==> Updated local gh-pages branch (--local specified; skipped git push)."
  echo "    Run 'git push origin gh-pages' when ready to publish."
fi
