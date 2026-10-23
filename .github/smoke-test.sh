#!/usr/bin/env bash
# Starts a real Paper server with the freshly built ForgeboundTeams.jar, runs a few console commands
# and fails if the plugin doesn't load cleanly. Used by the GitHub Actions build.
set -euo pipefail

MC_VERSION="${MC_VERSION:-1.21.11}"
JAR="$PWD/target/ForgeboundTeams.jar"
DIR="$PWD/target/smoke"
LOG="$DIR/console.log"

rm -rf "$DIR"
mkdir -p "$DIR/plugins"
cp "$JAR" "$DIR/plugins/"
cd "$DIR"

# --- download Paper (newer "fill" API first, the old v2 API as a fallback)
URL=$(curl -fsSL "https://fill.papermc.io/v3/projects/paper/versions/$MC_VERSION/builds/latest" \
        | jq -r '.downloads."server:default".url // empty' || true)
if [ -z "$URL" ]; then
  BUILD=$(curl -fsSL "https://api.papermc.io/v2/projects/paper/versions/$MC_VERSION/builds" | jq -r '.builds[-1].build')
  URL="https://api.papermc.io/v2/projects/paper/versions/$MC_VERSION/builds/$BUILD/downloads/paper-$MC_VERSION-$BUILD.jar"
fi
echo "Paper: $URL"
curl -fsSL -o paper.jar "$URL"

# --- a small, fast test world
echo "eula=true" > eula.txt
cat > server.properties <<'EOF'
level-type=minecraft\:flat
generate-structures=false
view-distance=2
simulation-distance=2
spawn-protection=0
online-mode=false
server-port=25599
EOF

# --- start the server with the console on a pipe
mkfifo console.in
java -Xms1G -Xmx2G -jar paper.jar --nogui < console.in > "$LOG" 2>&1 &
PID=$!
exec 3> console.in

wait_for() {   # wait_for <pattern> <seconds>
  for _ in $(seq 1 "$2"); do
    if grep -q -- "$1" "$LOG"; then return 0; fi
    if ! kill -0 "$PID" 2>/dev/null; then return 1; fi
    sleep 1
  done
  return 1
}

FAILED=0
fail() { echo "::error::$1"; FAILED=1; }

if ! wait_for 'Done (' 600; then
  fail "The server didn't finish starting"
else
  echo "team list" >&3
  wait_for 'No teams yet' 30 || fail "/team list printed nothing (command not registered?)"
  echo "team top" >&3
  sleep 3
fi

echo "stop" >&3
for _ in $(seq 1 60); do kill -0 "$PID" 2>/dev/null || break; sleep 1; done
kill -9 "$PID" 2>/dev/null || true
exec 3>&-

# --- check what the plugin did
grep -q '\[ForgeboundTeams\] [0-9]* teams loaded' "$LOG" || fail "ForgeboundTeams didn't finish enabling"
if grep -n 'Error occurred while enabling ForgeboundTeams\|Could not load.*ForgeboundTeams' "$LOG"; then
  fail "ForgeboundTeams failed to load"
fi
if grep -n 'at cz\.forgebound\.teams\.' "$LOG"; then
  fail "An exception went through ForgeboundTeams's code"
fi
if grep -nE '(WARN|ERROR)\]: \[ForgeboundTeams\]' "$LOG"; then
  fail "ForgeboundTeams logged warnings or errors"
fi

echo "----- server console (Forgebound lines) -----"
grep -n 'ForgeboundTeams\|Teams' "$LOG" | head -60 || true
if [ "$FAILED" -ne 0 ]; then
  echo "----- full console -----"
  cat "$LOG"
  exit 1
fi
echo "Smoke test passed."
