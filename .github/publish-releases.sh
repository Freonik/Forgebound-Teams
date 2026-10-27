#!/usr/bin/env bash
# Publishes this build to the public releases repository and records it in latest.json there
# (the plugins' updaters read that file). The code repository itself can stay private.
#   publish-releases.sh <latest.json key> <tag prefix> <title> <mark as latest: true|false> <files...>
# env: GH_TOKEN (RELEASES_TOKEN secret), VERSION, GITHUB_RUN_NUMBER, optional PACK_SHA1, notes in notes.md
set -euo pipefail
KEY=$1; PREFIX=$2; TITLE=$3; LATEST=$4; shift 4
REL=Freonik/Forgebound-releases
if [ -z "${GH_TOKEN:-}" ]; then
  echo "::warning::RELEASES_TOKEN secret is not set - not published to $REL"
  exit 0
fi
TAG="${PREFIX}-v${VERSION}-build.${GITHUB_RUN_NUMBER}"
gh release create "$TAG" "$@" -R "$REL" --title "$TITLE" --notes-file notes.md --latest="$LATEST"
BASE="https://github.com/$REL/releases/download/$TAG"
git clone -q "https://x-access-token:${GH_TOKEN}@github.com/$REL.git" _releases
cd _releases
git config user.name "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
for attempt in 1 2 3 4 5; do
  python3 - "$KEY" "$TAG" "$BASE" "$@" <<'PY'
import json, os, sys
key, tag, base, files = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4:]
path = 'latest.json'
data = json.load(open(path)) if os.path.exists(path) else {}
entry = {'version': os.environ['VERSION'], 'tag': tag}
for f in files:
    name = os.path.basename(f)
    entry['pack' if name.endswith('-pack.zip') else 'jar'] = f'{base}/{name}'
if os.environ.get('PACK_SHA1'):
    entry['pack_sha1'] = os.environ['PACK_SHA1']
data[key] = entry
json.dump(data, open(path, 'w'), indent=2, sort_keys=True)
open(path, 'a').write('\n')
PY
  git add latest.json
  git commit -q -m "$KEY $VERSION (build $GITHUB_RUN_NUMBER)" || exit 0
  if git push -q; then exit 0; fi
  git reset -q --hard HEAD~1
  git pull -q --rebase
done
echo "::error::couldn't update latest.json"; exit 1
