#!/usr/bin/env bash
# Pre-flight for cutting a release tag. Read-only: it checks and prints the commands, it never tags or pushes.
# See docs/development/RELEASING.md.
#
# The current version is released from main; a historical version is released from its release/vX.Y branch.
#
#   scripts/release-check.sh v1.1-beta.1
#   scripts/release-check.sh v1.1
#   scripts/release-check.sh v1.1.1
set -euo pipefail

usage() {
  echo "usage: $0 vX.Y | vX.Y.Z | vX.Y-beta.N | vX.Y.Z-beta.N" >&2
  exit 2
}
fail() {
  echo "release-check: $*" >&2
  exit 1
}

tag="${1:-}"
[[ "$tag" =~ ^v((0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]?)(\.(0|[1-9][0-9]?))?(-beta\.[1-9][0-9]?)?)$ ]] || usage
version="${BASH_REMATCH[1]}"
case "$version" in *-beta.*) channel=prerelease ;; *) channel=stable ;; esac
line="$(cut -d. -f1,2 <<<"${version%%-*}")"

cd "$(git rev-parse --show-toplevel)"

gradle_version="$(sed -n 's/^val appVersionName = "\(.*\)"$/\1/p' src/android/app/build.gradle.kts)"
[ -n "$gradle_version" ] || fail "cannot read appVersionName from src/android/app/build.gradle.kts"
[ "$gradle_version" = "$version" ] || fail "tag is $tag but appVersionName is \"$gradle_version\""

# The current version is released from main. A version that has become history has its own branch
# (release/vX.Y) and is released from there; main must not tag it.
branch="$(git rev-parse --abbrev-ref HEAD)"
history_branch="release/v$line"
if [ -n "$(git ls-remote --heads origin "refs/heads/$history_branch")" ]; then
  [ "$branch" = "$history_branch" ] || fail "$line is a historical version with its own branch: cut $tag on $history_branch (this is $branch)"
else
  [ "$branch" = main ] || fail "$tag is cut on main (the current version has no branch of its own), but this is $branch"
fi

[ -z "$(git status --porcelain)" ] || fail "working tree is not clean"

git fetch -q origin
[ "$(git rev-parse HEAD)" = "$(git rev-parse "origin/$branch" 2>/dev/null || echo none)" ] \
  || fail "HEAD is not the tip of origin/$branch (push or pull first)"

if git rev-parse -q --verify "refs/tags/$tag" >/dev/null || [ -n "$(git ls-remote --tags origin "refs/tags/$tag")" ]; then
  fail "tag $tag already exists"
fi

grep -Eq "^## ${version//./\\.}( |$)" CHANGELOG.md || fail "CHANGELOG.md has no \"## $version\" heading"

echo "ok: $tag  ($channel, branch $branch, appVersionName $gradle_version)"
echo
echo "Build the signed APK with the production RELEASE_* keystore, then verify it:"
echo "  scripts/verify-android-release.sh path/to/app-release.apk"
echo
echo "Then tag and publish:"
echo "  git tag -a $tag -m \"Minis for Android $version\""
echo "  git push origin $tag"
if [ "$channel" = prerelease ]; then
  echo "  gh release create $tag --verify-tag --prerelease --title \"$version\" --notes-file NOTES.md [APK]"
else
  echo "  gh release create $tag --verify-tag --title \"$version\" --notes-file NOTES.md [APK]"
fi
