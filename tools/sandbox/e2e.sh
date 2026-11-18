#!/usr/bin/env bash
# The first-run checklist of CLAUDE.md, run against a real, sandboxed PhpStorm with this plugin (macOS).
#   tools/sandbox/e2e.sh            builds, starts a fresh sandbox (demo.sh), runs every step, quits
#   KEEP=1 tools/sandbox/e2e.sh     leaves the sandbox running afterwards (tools/sandbox/cmd.sh to poke at it)
#   EBDEMO_ROOT=/tmp/x EBDEMO_PORT=8766 tools/sandbox/e2e.sh   a second sandbox next to one already running
# Prints PASS / FAIL per check and exits non-zero on any FAIL. Your own PhpStorm and settings are not touched.
set -uo pipefail
cd "$(dirname "$0")/../.."
ROOT="${EBDEMO_ROOT:-/private/tmp/ebdemo}"
PORT="${EBDEMO_PORT:-8765}"
SITE="http://127.0.0.1:$PORT"
LOG="$ROOT/ide/log/idea.log"
PASSED=0
FAILED=()

c() { tools/sandbox/cmd.sh -d "$ROOT" "$@"; }
pass() { PASSED=$((PASSED + 1)); echo "  PASS  $1"; }
fail() { FAILED+=("$1"); echo "  FAIL  $1${2:+  ($2)}"; }
section() { echo; echo "== $*"; }
# contains <description> <needle> <command…>: passes when the command's output contains needle (waits up to 15 s)
contains() {
  local d="$1" needle="$2"; shift 2
  local out
  for _ in $(seq 1 15); do
    out=$("$@" 2>&1)
    [[ "$out" == *"$needle"* ]] && { pass "$d"; return 0; }
    sleep 1
  done
  fail "$d" "got: $(echo "$out" | head -6 | tr '\n' ' ')"
}
until_has() {
  local t="$1" needle="$2"; shift 2
  for _ in $(seq 1 "$t"); do [[ "$("$@" 2>/dev/null || true)" == *"$needle"* ]] && return 0; sleep 1; done
  return 1
}
ready() {
  until_has 180 "project " c windows || { echo "sandbox did not start"; exit 1; }
  sleep 6
}
# page coordinates of an element's centre, read back through the page title
centre() {
  c js "var r=document.getElementById('$1').getBoundingClientRect(); document.title='at '+Math.round(r.left+r.width/2)+' '+Math.round(r.top+r.height/2)" >/dev/null
  until_has 10 "'at " c tabs || return 1
  c tabs | sed -n "s/^\* browser 'at \([0-9]*\) \([0-9]*\)'.*/\1 \2/p"
}

./build.sh >/dev/null || { echo "build failed"; exit 1; }
tools/sandbox/demo.sh "$ROOT" >/dev/null
ready
c closefiles >/dev/null

section "1 Open a URL as a tab"
c open "$SITE/" >/dev/null
contains "tab shows the page title" "* browser 'EB Home'" c tabs
contains "address bar shows the URL" "address $SITE/" c state
contains "Chromium loaded it" "cef $SITE/" c state
contains "the page's favicon becomes the tab icon" "favicon 16x16" c state

section "2 Navigation"
c js "document.getElementById('next').click()" >/dev/null
contains "a link loads in the same tab" "* browser 'EB Second'" c tabs
c js "history.back()" >/dev/null
contains "back" "* browser 'EB Home'" c tabs
c address >/dev/null; sleep 1; c type "127.0.0.1:$PORT/second.html" >/dev/null; c key enter >/dev/null
contains "address bar: typed address without scheme loads" "cef $SITE/second.html" c state
c js "history.back()" >/dev/null
until_has 10 "* browser 'EB Home'" c tabs

section "3 New-window links"
read -r X Y < <(centre blank)
c click "$X" "$Y" >/dev/null
contains "target=_blank opens a new editor tab" "* browser 'EB Third'" c tabs

section "4 Zoom and shortcuts"
c stroke "meta MINUS" >/dev/null; sleep 1; c stroke "meta MINUS" >/dev/null
contains "⌘- twice: 80%, label shown" "zoomLabel 80%" c state
c stroke "meta 0" >/dev/null
contains "⌘0 resets" "zoomLabel (hidden)" c state
c stroke "meta L" >/dev/null
contains "⌘L focuses the address bar" "focus JBTextField" c state
c key esc >/dev/null

section "5 New Tab"
c action EditorBrowser.NewTab >/dev/null
contains "New Browser Tab opens a blank tab" "* browser 'New Tab'" c tabs
contains "its address bar is focused, ready to type over" "focus JBTextField" c state
sleep 1; c type "127.0.0.1:$PORT/second.html" >/dev/null; c key enter >/dev/null
contains "typing an address into it loads the page" "* browser 'EB Second'" c tabs
contains "the macOS keymap shows New Browser Tab as ⌥⇧⌘T" "shortcut ⌥⇧⌘T" c shortcut EditorBrowser.NewTab
browsers() { c tabs | grep -c "browser '"; }
n=$(browsers)
c stroke "meta alt shift T" >/dev/null
until_has 10 "* browser 'New Tab'" c tabs && [ "$(browsers)" -gt "$n" ] \
  && pass "⌥⌘⇧T (the real keystroke) opens a blank tab" || fail "⌥⌘⇧T opens a blank tab" "$(c tabs | head -6 | tr '\n' ' ')"
c key esc >/dev/null
contains "the main toolbar has a globe New Browser Tab button" "button globe" c toolbar
n=$(browsers)
c toolbar click >/dev/null; sleep 2
[ "$(browsers)" -gt "$n" ] && pass "clicking it opens a blank tab" || fail "clicking the toolbar button opens a blank tab"

section "6 Split and Open URL dialog"
c action SplitVertically >/dev/null
contains "Split Right shows the page in two splits" "splits 2" c tabs
c action EditorBrowser.OpenUrl >/dev/null
until_has 20 "'Open URL in Editor Tab'" c windows
c type "127.0.0.1:$PORT/second.html" >/dev/null; c key enter >/dev/null
contains "Tools › Open URL in Editor Tab… opens a new tab" "* browser 'EB Second'" c tabs

section "7 Selected URL in code"
c file "$ROOT/project/links.md" >/dev/null; sleep 2
c select "$SITE/second.html" >/dev/null; sleep 1
c action EditorBrowser.OpenSelection >/dev/null
contains "right-click › Open in Editor Tab" "* browser 'EB Second'" c tabs

section "8 Dev server auto-detect"
c devserver "http://127.0.0.1:59321/" >/dev/null
contains "a run configuration printing a localhost URL opens a tab" "127.0.0.1:59321" c tabs
before2=$(c tabs | grep -c "browser '")
c devserver "http://127.0.0.1:59321/" >/dev/null
sleep 2
after2=$(c tabs | grep -c "browser '")
[ "$after2" = "$before2" ] && pass "restarting the dev server reuses the tab ($after2)" \
  || fail "restarting the dev server reuses the tab" "$before2 → $after2"
# a server started outside a run configuration (the Terminal) on a watched dev-server port; note that your own
# PhpStorm, with this plugin installed, notices it too and opens a localhost:4321 tab
DEVPORT=4321
if lsof -nP -iTCP:$DEVPORT -sTCP:LISTEN >/dev/null 2>&1; then
  fail "a dev server started in the Terminal opens a tab" "port $DEVPORT is already in use on this machine"
else
  python3 -m http.server $DEVPORT --bind 127.0.0.1 --directory "$ROOT/site" >/dev/null 2>&1 & DEVPID=$!
  contains "a dev server started in the Terminal (port $DEVPORT) opens a tab" "localhost:$DEVPORT" c tabs
  before3=$(c tabs | grep -c "browser '")
  kill $DEVPID; wait $DEVPID 2>/dev/null; sleep 4
  python3 -m http.server $DEVPORT --bind 127.0.0.1 --directory "$ROOT/site" >/dev/null 2>&1 & DEVPID=$!
  sleep 5
  after3=$(c tabs | grep -c "browser '")
  kill $DEVPID; wait $DEVPID 2>/dev/null
  [ "$after3" = "$before3" ] && pass "restarting it reuses the tab ($after3)" \
    || fail "restarting it reuses the tab" "$before3 → $after3"
fi

section "9 Restart"
before=$(c tabs | grep -c "browser '")
tools/sandbox/demo.sh --restart "$ROOT" >/dev/null
ready
contains "browser tabs reopen after a restart ($before)" "browser 'EB" c tabs
after=$(c tabs | grep -c "browser '")
[ "$after" = "$before" ] && pass "same number of browser tabs ($after)" || fail "same number of browser tabs" "$before → $after"

section "Log"
errors=$(grep -E "ERROR|SEVERE" "$LOG" | grep -ciE "netonic|editor-browser|jcef" || true)
[ "$errors" = 0 ] && pass "no errors from the plugin in idea.log" || fail "no errors from the plugin in idea.log" "$errors lines"

[ -n "${KEEP:-}" ] || tools/sandbox/demo.sh --stop "$ROOT" >/dev/null
echo
echo "$PASSED passed, ${#FAILED[@]} failed"
for f in ${FAILED[@]+"${FAILED[@]}"}; do echo "  FAILED: $f"; done
[ ${#FAILED[@]} -eq 0 ]
