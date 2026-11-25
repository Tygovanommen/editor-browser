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

section "4 View page source"
c rclick 200 200 >/dev/null
until_has 10 "item 'View Page Source'" c menu
c menu "View Page Source" >/dev/null
contains "right-click › View Page Source opens the source in a new tab" \
  "* browser 'view-source:127.0.0.1:$PORT/third.html'" c tabs
contains "its address bar keeps the view-source: prefix" "address view-source:$SITE/third.html" c state

section "5 Zoom and shortcuts"
c stroke "meta MINUS" >/dev/null; sleep 1; c stroke "meta MINUS" >/dev/null
contains "⌘- twice: 80%, label shown" "zoomLabel 80%" c state
c stroke "meta 0" >/dev/null
contains "⌘0 resets" "zoomLabel (hidden)" c state
# more <item> [<subitem>]: picks an item of the toolbar's ⋮ menu (Device Width and Zoom are there by default)
more() {
  c button "More" >/dev/null
  until_has 10 "item '$1'" c menu || { echo "no '$1' in the ⋮ menu"; return 1; }
  c menu "$1" >/dev/null
  [ -z "${2:-}" ] && return 0
  until_has 10 "item '$2'" c menu || { echo "no '$2' in the '$1' submenu"; return 1; }
  c menu "$2" >/dev/null
}
# device width: ⋮ › Device Width lays the page out at a phone width, centred; it scales with the zoom
innerWidth() { c js "document.title='w '+window.innerWidth" >/dev/null; sleep 1; c tabs | sed -n "s/^\* browser 'w \([0-9]*\)'.*/\1/p"; }
more "Device Width" "Phone (390 px)"
contains "Device Width › Phone: label shows 390 px" "widthLabel 390 px" c state
contains "the page area is 390 px wide" "page 390 of" c state
w=$(innerWidth); [ "$w" = 390 ] && pass "the page sees a 390 px viewport" || fail "the page sees a 390 px viewport" "innerWidth $w"
more "Zoom Out"
contains "⋮ › Zoom Out: 90%, the column narrows (351 px)" "page 351 of" c state
more "Zoom In"
contains "⋮ › Zoom In: back to 100%, 390 px again" "page 390 of" c state
more "Device Width" "Full Width"
contains "Full Width hides the label" "widthLabel (hidden)" c state
contains "and the page fills the tab again" "at x 0" c state
c js "location.reload()" >/dev/null; sleep 2
c stroke "meta L" >/dev/null
contains "⌘L focuses the address bar" "focus JBTextField" c state
c key esc >/dev/null

section "5b Toolbar layout (Settings › Tools › Editor Browser)"
contains "default: DevTools on the toolbar" "button 'Open DevTools'" c buttons
[[ "$(c buttons)" != *"button 'Zoom In'"* ]] && pass "default: zoom buttons only in ⋮" || fail "default: zoom buttons only in ⋮" "$(c buttons | tr '\n' ' ')"
c button "More" >/dev/null
contains "⋮ ends with Customize Toolbar…" "item 'Customize Toolbar…'" c menu
contains "⋮ has Share Preview by default" "item 'Share Preview'" c menu
c menu "Find in Page" >/dev/null
contains "⋮ › Find in Page opens the find bar" "find shown" c state
c call closeFind >/dev/null
c layout zoom=toolbar devTools=menu newTab=hidden >/dev/null
contains "zoom moved to the toolbar shows up there" "button 'Zoom In'" c buttons
out=$(c buttons)
[[ "$out" != *"button 'Open DevTools'"* && "$out" != *"button 'New Browser Tab'"* ]] \
  && pass "DevTools (moved to ⋮) and New Tab (hidden) leave the toolbar" || fail "DevTools and New Tab leave the toolbar" "$(echo "$out" | tr '\n' ' ')"
c button "More" >/dev/null
out=$(until_has 10 "item 'Open DevTools'" c menu && c menu)
[[ "$out" == *"item 'Open DevTools'"* && "$out" != *"item 'Zoom In'"* && "$out" != *"item 'New Browser Tab'"* ]] \
  && pass "⋮ now has DevTools, no zoom, no New Tab" || fail "⋮ follows the layout" "$(echo "$out" | tr '\n' ' ')"
c menu "Find in Page" >/dev/null; c call closeFind >/dev/null
c layout default >/dev/null
contains "back to the default layout" "button 'New Browser Tab'" c buttons

section "6 New Tab"
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

section "7 Split and Open URL dialog"
c action SplitVertically >/dev/null
contains "Split Right shows the page in two splits" "splits 2" c tabs
c action EditorBrowser.OpenUrl >/dev/null
until_has 20 "'Open URL in Editor Tab'" c windows
c type "127.0.0.1:$PORT/second.html" >/dev/null; c key enter >/dev/null
contains "Tools › Open URL in Editor Tab… opens a new tab" "* browser 'EB Second'" c tabs

section "8 Selected URL in code"
c file "$ROOT/project/links.md" >/dev/null; sleep 2
c select "$SITE/second.html" >/dev/null; sleep 1
c action EditorBrowser.OpenSelection >/dev/null
contains "right-click › Open in Editor Tab" "* browser 'EB Second'" c tabs

section "8b HTML file of the project"
PAGE="$ROOT/project/web pages/page.html"
c file "$PAGE" >/dev/null; sleep 2
c action EditorBrowser.OpenFile >/dev/null
contains "Open In › Browser Tab shows the file as a page, its relative script loaded" \
  "* browser 'EB Local' file://$ROOT/project/web%20pages/page.html" c tabs
n=$(c tabs | grep -c "browser '")
c file "$PAGE" >/dev/null; sleep 2
c action EditorBrowser.OpenFile >/dev/null
until_has 10 "* browser 'EB Local'" c tabs
[ "$(c tabs | grep -c "browser '")" = "$n" ] && pass "running it again reuses that tab" \
  || fail "running it again reuses that tab" "$n → $(c tabs | grep -c "browser '")"

section "9 Dev server auto-detect"
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

section "10 Find in page, console errors, reload on save"
st() { c state | grep -E "^(address|find|errors|focus) "; }
selected() { c tabs | grep "^\\* "; }
# a dialog an earlier check couldn't type into (no keyboard focus) would hold back every invokeLater update here
c closedialogs >/dev/null
# one split, so a tab opened here is the selected editor even when the sandbox window has no keyboard focus
c action UnsplitAll >/dev/null; sleep 1
c open "$SITE/" >/dev/null
until_has 15 "* browser 'EB Home'" c tabs
c call openFind >/dev/null
contains "the find bar opens" "find shown ''" st
c settext findField page >/dev/null
contains "typing counts the matches" "find shown '3 matches'" st
c settext findField "nothing like this" >/dev/null
contains "no match says so" "find shown 'No matches'" st
c settext findField "" >/dev/null
c call closeFind >/dev/null
contains "closing hides it" "find hidden" st
c stroke "meta F" >/dev/null
contains "⌘F opens it with its field focused" "find shown '' focused" st
c key esc >/dev/null
contains "Esc in the field closes it" "find hidden" st
c call closeFind >/dev/null
c open "$SITE/error.html" >/dev/null
contains "console errors show as a badge (2)" "errors 2" st
c js "location.href='/second.html'" >/dev/null
until_has 15 "* browser 'EB Second'" c tabs
contains "a new page clears the badge" "errors 0" st
c js "document.title='EB Marked'" >/dev/null
until_has 10 "* browser 'EB Marked'" c tabs
c save "$ROOT/project/links.md" >/dev/null
contains "saving a project file reloads a local page" "* browser 'EB Second'" selected
c open "$SITE/hmr.html" >/dev/null
until_has 15 "* browser 'EB HMR'" c tabs; sleep 1
c js "document.title='EB HMR Marked'" >/dev/null
until_has 10 "* browser 'EB HMR Marked'" c tabs
c save "$ROOT/project/app.js" >/dev/null; sleep 3
[[ "$(c tabs)" == *"* browser 'EB HMR Marked'"* ]] && pass "a page with a dev-server client isn't reloaded for a .js save" \
  || fail "a page with a dev-server client isn't reloaded for a .js save" "$(c tabs | grep '^\*')"
c save "$ROOT/project/index.php" >/dev/null
contains "… but is for a .php save" "* browser 'EB HMR' " selected
c js "document.title='EB HMR Marked'" >/dev/null
until_has 10 "* browser 'EB HMR Marked'" c tabs
c file "$ROOT/project/index.php" >/dev/null; sleep 1
c save "$ROOT/project/index.php" >/dev/null; sleep 3
[[ "$(c tabs)" == *"browser 'EB HMR Marked'"* ]] && pass "a tab in the background isn't reloaded yet" \
  || fail "a tab in the background isn't reloaded yet" "$(c tabs | grep HMR)"
c focustab "EB HMR Marked" >/dev/null
contains "… it reloads when shown" "* browser 'EB HMR' " selected

section "10b Share preview"
c closedialogs >/dev/null
c open "$SITE/share.html" >/dev/null
until_has 15 "* browser 'EB Share'" c tabs
c call openSharePreview >/dev/null
contains "Share Preview shows the og: title in a popover" "label EB Share card" c popup
contains "… with og:site_name and og:description" "label What a link to this page shows" c popup
contains "… and downloads og:image: a 1x1 image is too small" "label Image is 1 × 1 px" c popup
c popup close >/dev/null
contains "the popover closes" "no popup" c popup
more "Share Preview"
contains "⋮ › Share Preview opens it too" "label EB Share card" c popup
c popup close >/dev/null
c layout sharePreview=toolbar >/dev/null
contains "Share Preview moved to the toolbar" "button 'Share Preview'" c buttons
c button "Share Preview" >/dev/null
contains "… and its button opens the popover" "label EB Share card" c popup
# captured first: with pipefail, grep -q closing the pipe early would fail the check
sizes=$(c popup sizes)
grep -qE "^ImageBox [1-9][0-9]*x[1-9][0-9]* " <<<"$sizes" && pass "… with room for the image (not collapsed)" \
  || fail "… with room for the image (not collapsed)" "$(grep ImageBox <<<"$sizes")"
c open "$SITE/second.html" >/dev/null
until_has 15 "* browser 'EB Second'" c tabs
c focustab "EB Share" >/dev/null
c call openSharePreview >/dev/null
until_has 10 "label EB Share card" c popup
c focustab "EB Second" >/dev/null
contains "switching to another tab closes it" "no popup" c popup
c focustab "EB Share" >/dev/null
c popup close >/dev/null
c layout default >/dev/null
c open "$SITE/second.html" >/dev/null
until_has 15 "* browser 'EB Second'" c tabs
c call openSharePreview >/dev/null
contains "a page without share tags: falls back to <title>" "label No og:title: apps fall back to <title>" c popup
contains "… and says the image is missing" "label No og:image" c popup
c popup close >/dev/null

section "10c Light / dark mode"
c closedialogs >/dev/null
c open "$SITE/scheme.html" >/dev/null
until_has 15 "* browser 'EB Scheme " c tabs
system=$(c tabs | sed -n "s/^\* browser 'EB Scheme \([a-z]*\)'.*/\1/p")
more "Color Scheme" "Dark"
contains "⋮ › Color Scheme › Dark: the page sees prefers-color-scheme: dark" "* browser 'EB Scheme dark'" c tabs
contains "… and a Dark label shows" "schemeLabel Dark" c state
c js "document.title='EB marker'; location.href='/scheme.html?again'" >/dev/null
contains "it stays dark on the next page" "* browser 'EB Scheme dark'" c tabs
more "Color Scheme" "Light"
contains "Light: the page sees light" "* browser 'EB Scheme light'" c tabs
contains "… label Light" "schemeLabel Light" c state
more "Color Scheme" "System"
contains "System: the page sees the system's again ($system)" "* browser 'EB Scheme $system'" c tabs
contains "… and the label is gone" "schemeLabel (hidden)" c state
contains "a page without a background of its own shows white behind it" "canvas #ffffff" c state
c open "$SITE/both.html" >/dev/null
until_has 15 "* browser 'EB Both'" c tabs
contains "a page in color-scheme light dark: white in light" "canvas #ffffff" c state
more "Color Scheme" "Dark"
contains "… and Chromium's dark canvas when set to Dark" "canvas #121212" c state
c focustab "EB Scheme $system" >/dev/null
# left on Dark for the restart check (section 11)
more "Color Scheme" "Dark"
until_has 10 "* browser 'EB Scheme dark'" c tabs

section "10d Clear site data"
c open "$SITE/storage.html" >/dev/null
until_has 15 "* browser 'EB Storage " c tabs
c js "document.cookie='eb=1; path=/'; localStorage.setItem('eb', '1'); location.reload()" >/dev/null
contains "the page set a cookie and a localStorage value" "* browser 'EB Storage cookie=1 local=1'" c tabs
more "Clear Site Data"
contains "⋮ › Clear Site Data deletes both and reloads" "* browser 'EB Storage cookie=- local=-'" c tabs

section "10e HTTP basic auth"
c closedialogs >/dev/null
c open "$SITE/auth/" >/dev/null
contains "a site asking for basic auth gets a Sign In dialog" "label 127.0.0.1:$PORT asks for a username and password" c auth
contains "… naming the realm" "EB Staging" c auth
contains "… and that http sends the password readable" "sent readable" c auth
c auth cancel >/dev/null
contains "Cancel leaves a Sign-in needed page" "* browser 'Sign-in needed'" c tabs
c js "location.reload()" >/dev/null
until_has 15 "asks for a username" c auth
[[ "$(c auth)" != *"didn't accept"* ]] && pass "after Cancel it asks again, without an error" || fail "after Cancel it asks again, without an error" "$(c auth | tr '\n' ' ')"
c auth eb wrong >/dev/null
contains "a wrong password: it asks again, saying so" "didn't accept that username or password" c auth
c auth eb secret >/dev/null
contains "the right one loads the page" "* browser 'EB Signed In'" c tabs
c open "$SITE/auth/" >/dev/null
until_has 15 "* browser 'EB Signed In'" c tabs
[[ "$(c auth)" == "no auth dialog" ]] && pass "another tab on the site doesn't ask again (same session)" \
  || fail "another tab on the site doesn't ask again" "$(c auth | tr '\n' ' ')"

section "10f Loading, failed pages, server errors"
# server.py: /slow/<ms> answers late, /stall/<ms> has an image that takes that long, /status/<code>, /drop
ld() { c state | grep -E "^(loading|progress|tabicon|http|notice) "; c buttons | grep -E "'(Stop|Reload)'"; }
c closedialogs >/dev/null
c open "$SITE/slow/6000" >/dev/null
contains "while a page loads: a Stop (X) button instead of Reload" "button 'Stop'" ld
contains "… the progress line" "progress shown" ld
contains "… and a spinner as the tab icon" "tabicon LOADING" ld
contains "no answer after 3 s: the notice bar says the tab is waiting for the server" \
  "notice waiting: Waiting for 127.0.0.1:$PORT" ld
contains "once loaded: Reload again, the notice gone" "button 'Reload'" ld
sleep 1 # the progress line runs to the end and fades out first
out=$(ld)
[[ "$out" == *"notice (hidden)"* && "$out" == *"tabicon NORMAL"* && "$out" == *"progress hidden"* ]] \
  && pass "… no progress line, the normal tab icon" || fail "… no progress line, the normal tab icon" "$(echo "$out" | tr '\n' ' ')"
c open "$SITE/stall/30000" >/dev/null
until_has 10 "* browser 'EB Stall'" c tabs
contains "a page still loading an image: Stop is shown" "button 'Stop'" ld
c button Stop >/dev/null
contains "… and clicking it stops the load" "loading false" ld
c button Reload >/dev/null
out=$(c button Stop)
sleep 1
[[ "$out" == *clicked* && "$(ld)" == *"loading true"* ]] \
  && pass "a second click right after Reload (a double click) doesn't stop the load" \
  || fail "a double click on Reload doesn't stop the load" "$out / $(ld | tr '\n' ' ')"
c button Stop >/dev/null
# a local server that isn't running yet: the tab waits for it and reloads when it is up
RPORT=59996
c open "http://localhost:$RPORT/" >/dev/null
contains "a refused connection: the notice bar says so, in words" \
  "notice failed: Can't open localhost:$RPORT  Nothing is answering on localhost:$RPORT. Is the server running?" ld
contains "… the tab icon turns into an error icon" "tabicon FAILED" ld
contains "… a local server: it waits for it (Stop Waiting)" "[Try Again] [Stop Waiting]" ld
c call userReload >/dev/null
contains "Reload while it is still down: \"Still can't open\"" "Still can't open localhost:$RPORT" ld
python3 -m http.server $RPORT --bind 127.0.0.1 --directory "$ROOT/site" >/dev/null 2>&1 & RPID=$!
contains "the server starts: the tab reloads by itself" "* browser 'EB Home'" c tabs
out=$(ld)
[[ "$out" == *"notice (hidden)"* && "$out" == *"tabicon NORMAL"* ]] && pass "… the notice and the error icon are gone" \
  || fail "… the notice and the error icon are gone" "$(echo "$out" | tr '\n' ' ')"
kill $RPID; wait $RPID 2>/dev/null
c call userReload >/dev/null
until_has 15 "Stop Waiting" ld
c notice "Stop Waiting" >/dev/null
out=$(ld)
[[ "$out" == *"notice failed: "* && "$out" != *"Stop Waiting"* && "$out" != *"reloads when"* ]] \
  && pass "Stop Waiting: the notice stays, without the wait" || fail "Stop Waiting" "$(echo "$out" | tr '\n' ' ')"
c open "$SITE/drop" >/dev/null
contains "a connection closed without an answer: says so (ERR_EMPTY_RESPONSE)" "closed the connection without an answer" ld
c open "http://no-such-host.invalid/" >/dev/null
contains "an unknown host: says so" "Can't find no-such-host.invalid" ld
c open "$SITE/status/500" >/dev/null
until_has 15 "* browser 'EB Status 500'" c tabs
contains "an HTTP 500 answer: a 500 badge" "http 500" ld
contains "… and the error tab icon" "tabicon FAILED" ld
c js "location.href='/status/404'" >/dev/null
until_has 15 "* browser 'EB Status 404'" c tabs
contains "a 404: a 404 badge, the normal tab icon" "tabicon NORMAL" ld
contains "… badge 404" "http 404" ld
c js "location.href='/second.html'" >/dev/null
until_has 15 "* browser 'EB Second'" c tabs
contains "a 200 page: no badge" "http -" ld
c cdp Page.crash >/dev/null
contains "the page's process crashes: the notice bar says so" "notice crashed: The page crashed" ld
c notice Reload >/dev/null
contains "… its Reload brings the page back" "notice (hidden)" ld
contains "… the page shows again" "* browser 'EB Second'" c tabs

section "11 Restart"
before=$(c tabs | grep -c "browser '")
c layout find=toolbar >/dev/null
tools/sandbox/demo.sh --restart "$ROOT" >/dev/null
ready
contains "browser tabs reopen after a restart ($before)" "browser 'EB" c tabs
contains "the source tab reopens as page source" "browser 'view-source:127.0.0.1:$PORT/third.html' view-source:" c tabs
contains "the HTML file tab reopens on the file" "browser 'EB Local' file://" c tabs
contains "the toolbar layout is kept (Find in Page on the toolbar)" "button 'Find in Page'" c buttons
c focustab "EB Scheme dark" >/dev/null
until_has 15 "* browser 'EB Scheme dark'" c tabs
c js "document.title='EB marker'; location.reload()" >/dev/null
contains "a tab set to Dark is still dark after the restart" "* browser 'EB Scheme dark'" c tabs
c layout default >/dev/null
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
