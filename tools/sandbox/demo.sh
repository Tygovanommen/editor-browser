#!/usr/bin/env bash
# A sandboxed PhpStorm with this plugin and two local test pages, for tests (e2e.sh).
#   tools/sandbox/demo.sh [demo-root]             new sandbox; default root /tmp/ebdemo; needs ./build.sh first
#   tools/sandbox/demo.sh --restart [demo-root]   quits and starts the same sandbox again, state kept
#                                                 (reinstalls the plugin from build/, so also for a new build)
#   tools/sandbox/demo.sh --probe [demo-root]     recompiles helper/Commands.java (loaded fresh for every command)
#   tools/sandbox/demo.sh --stop [demo-root]
# Starts PhpStorm with its own config / system / plugins under <root>/ide (your own PhpStorm and its settings are
# not touched; only the license key is copied) and serves <root>/site on http://127.0.0.1:$EBDEMO_PORT.
set -euo pipefail
cd "$(dirname "$0")/../.."
MODE=new
case "${1:-}" in --restart|--probe|--stop) MODE="${1#--}"; shift ;; esac
ROOT="${1:-/tmp/ebdemo}"
PORT="${EBDEMO_PORT:-8765}"
PS="${PHPSTORM_CONTENTS:-$HOME/Applications/PhpStorm.app/Contents}"
USER_CONFIG="${PHPSTORM_CONFIG:-$(ls -d "$HOME/Library/Application Support/JetBrains/PhpStorm"20* | tail -1)}"
JDK="$PS/jbr/Contents/Home/bin"
ZIP=$(ls build/distributions/editor-browser-*.zip | tail -1)
JCEF_LIB=$(ls -d "$PS"/plugins/*jcef*/lib | head -1)
CP=$(find "$PS/lib" "$JCEF_LIB" -name '*.jar' | tr '\n' ':')

case "$ROOT" in /tmp/*|/private/tmp/*) ;; *) echo "demo root must be under /tmp" >&2; exit 1 ;; esac
mkdir -p "$ROOT"
ROOT=$(cd "$ROOT" && pwd -P)
IDE="$ROOT/ide"
H="$ROOT/helper-build"

stop() {
  local pid
  pid=$(cat "$ROOT/ide.pid" 2>/dev/null || true)
  if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
    echo "stopping the sandboxed PhpStorm"
    # quit through the helper so settings and open tabs are saved; a kill (fallback) saves nothing
    rm -f "$ROOT/cmd/"*.cmd
    tools/sandbox/cmd.sh -d "$ROOT" quit >/dev/null 2>&1 || true
    for _ in $(seq 1 60); do kill -0 "$pid" 2>/dev/null || break; sleep 0.5; done
    kill "$pid" 2>/dev/null || true
    while kill -0 "$pid" 2>/dev/null; do sleep 0.5; done
    sleep 2 # the launcher of a new instance would otherwise find the old one's lock and hand over to it
  fi
  rm -f "$ROOT/cmd/"*.cmd
}

stop_server() {
  local pid
  pid=$(cat "$ROOT/server.pid" 2>/dev/null || true)
  [ -n "$pid" ] && kill "$pid" 2>/dev/null || true
  rm -f "$ROOT/server.pid"
}

start_server() {
  stop_server
  nohup python3 -m http.server "$PORT" --bind 127.0.0.1 --directory "$ROOT/site" >"$ROOT/server.log" 2>&1 &
  echo $! > "$ROOT/server.pid"
}

probe() {
  rm -rf "$H/commands" && mkdir -p "$H/commands"
  "$JDK/javac" --release 21 -proc:none -nowarn -cp "$CP:build/main" -d "$H/commands" tools/sandbox/helper/Commands.java
}

install_plugins() {
  rm -rf "$IDE/plugins" "$H/classes"
  mkdir -p "$IDE/plugins/ebdemo-helper/lib" "$H/classes"
  (cd "$IDE/plugins" && unzip -q "$OLDPWD/$ZIP")
  # the test helper (tools/sandbox/helper), only ever installed here
  "$JDK/javac" --release 21 -proc:none -nowarn -cp "$CP:build/main" -d "$H/classes" tools/sandbox/helper/ShotHelper.java
  cp -R tools/sandbox/helper/META-INF "$H/classes/"
  (cd "$H/classes" && zip -qr "$IDE/plugins/ebdemo-helper/lib/ebdemo-helper.jar" .)
  probe
}

start() {
  echo "starting sandboxed PhpStorm (log: $IDE/log/idea.log)"
  PHPSTORM_VM_OPTIONS="$IDE/phpstorm.vmoptions" nohup "$PS/MacOS/phpstorm" "$@" >>"$IDE/log/stdout.log" 2>&1 &
  echo $! > "$ROOT/ide.pid"
}

case "$MODE" in
  stop) stop; stop_server; exit 0 ;;
  probe) probe; exit 0 ;;
  restart) stop; install_plugins; start_server; start; exit 0 ;;
esac

stop
stop_server
rm -rf "$ROOT"
mkdir -p "$ROOT/site" "$ROOT/project"

cat > "$ROOT/site/index.html" <<'EOF'
<!doctype html>
<html><head><title>EB Home</title><link rel="icon" href="/favicon.png"></head>
<body>
  <h1>Editor Browser test page</h1>
  <p><a id="next" href="/second.html">Second page (same tab)</a></p>
  <p><a id="blank" href="/third.html" target="_blank">Third page (target=_blank: new tab)</a></p>
</body></html>
EOF
# a 1x1 PNG, just enough for BrowserFavicons to decode and scale into a tab icon
echo "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=" \
  | base64 -D > "$ROOT/site/favicon.png"
cat > "$ROOT/site/second.html" <<'EOF'
<!doctype html>
<html><head><title>EB Second</title></head><body><h1>Second</h1><a href="/">home</a></body></html>
EOF
cat > "$ROOT/site/third.html" <<'EOF'
<!doctype html>
<html><head><title>EB Third</title></head><body><h1>Third</h1></body></html>
EOF
echo "http://127.0.0.1:$PORT/second.html" > "$ROOT/project/links.md"

mkdir -p "$IDE/config/options" "$IDE/system" "$IDE/log"
cp "$USER_CONFIG/phpstorm.key" "$IDE/config/" 2>/dev/null || echo "no phpstorm.key: PhpStorm will ask for a license"
cp "$USER_CONFIG/disabled_plugins.txt" "$IDE/config/" 2>/dev/null || true
install_plugins

cat > "$IDE/config/options/trusted-paths.xml" <<EOF
<application>
  <component name="Trusted.Paths.Settings">
    <option name="TRUSTED_PATHS">
      <list>
        <option value="$ROOT" />
      </list>
    </option>
  </component>
</application>
EOF
cat > "$IDE/config/options/ide.general.xml" <<EOF
<application>
  <component name="GeneralSettings">
    <option name="confirmExit" value="false" />
    <option name="showTipsOnStartup" value="false" />
  </component>
</application>
EOF

{
  cat "$PS/bin/phpstorm.vmoptions"
  echo "-Didea.config.path=$IDE/config"
  echo "-Didea.system.path=$IDE/system"
  echo "-Didea.plugins.path=$IDE/plugins"
  echo "-Didea.log.path=$IDE/log"
  echo "-Didea.trust.all.projects=true"
  echo "-Didea.initially.ask.config=never"
  echo "-Debdemo.dir=$ROOT"
} > "$IDE/phpstorm.vmoptions"

start_server
start "$ROOT/project"
