# Editor Browser — context for Claude

PhpStorm plugin by Netonic: websites as normal editor tabs (next to files, in splits), using PhpStorm's bundled
embedded Chromium (JCEF). Sister project of `worktree-workspaces` (same build / release setup).
User-facing docs: `README.md` (public on GitHub, MIT).

Why it exists: PhpStorm's "Open in Editor Tab" for tool windows only works for tool windows that implement the
internal `ToolWindowInEditorSupport` (the Terminal does), so browser plugins such as GBrowser stay side panels.
This plugin makes each site a (virtual) file instead, so it is a real editor tab.

## Status (18 Nov 2026)

- Version 0.3.1, id `nl.netonic.editor-browser`, vendor Netonic, `since-build` 262 (PhpStorm 2026.2+).
  Distributed via GitHub Releases + `updatePlugins.xml` (custom plugin repository), not the Marketplace.
- Verified on PhpStorm 2026.3 (263.7307.215): `tools/api-check.sh` reports no internal / experimental / deprecated
  API use. `tools/sandbox/e2e.sh` passed 31/31 for 0.2.2. For 0.3.0 (19 Nov 2026) the last full run was 43/54:
  every non-keyboard check passed (incl. device widths, HTML file tabs, reload on save, console badge, find count),
  the 10 keyboard checks failed with `focus null` (sandbox window never got focus), and "the source tab reopens as
  page source" failed after the restart — likely the editor tab limit closing the oldest tabs now that more
  sections open tabs (unconfirmed). The keyboard checks need the sandbox window to keep focus: if many fail
  with `focus null`, something else took focus; rerun.
- `EditorEmptyTextProvider`/`EditorEmptyTextSink` (`impl` package) are `@ApiStatus.Internal` with no stable
  replacement; don't reintroduce an empty-editor-placeholder hint through them, `api-check.sh` will flag it.
- Not verified yet: building against 2026.2.3 in the release workflow (the Linux tarball's JCEF folder is matched
  as `plugins/*jcef*`); Windows / Linux at runtime.

## Model

- A tab is a `BrowserFile` (contentless `VirtualFile`) in `BrowserFileSystem`, protocol `editor-browser://<id>`,
  id = 12 hex chars. Being a real file system is what lets PhpStorm reopen tabs after a restart: it saves open
  files by URL and finds them again through `findFileByPath`, which only answers for ids `BrowserStore` knows.
- `BrowserStore` (app service, `editorBrowser.xml`): per id the current URL, page title, zoom % and device width, plus the
  recent typed addresses (15). Entries are not removed when a tab closes (closing a project closes its tabs too,
  which would lose them for the restart); the oldest are pruned past 200.
- Split Right on a browser tab gives a second `BrowserEditor` (second Chromium) for the same file / id: both write
  the same store entry; the last navigation wins for the tab title and the restart.

## Code map

`src/main/java/nl/netonic/browser/`

- `core/Urls` — **pure JDK** (covered by `CoreTests`): `normalize` (typed text → URL; local hosts http, else
  https), `looksLikeUrl` (for the selection action; rejects file names like `app.vue`), `label`, `shorten`,
  `viewSource` / `isViewSource`, `isHtmlFile` / `fileUrl` / `localPath` (local files as `file:` URLs and back; the
  label of a `file:` URL is the file name).
- `BrowserFileSystem`, `BrowserFile`, `BrowserFileType` (globe icon, registered without extensions).
- `BrowserEditorProvider` (`HIDE_DEFAULT_EDITOR`) → `BrowserEditor`: toolbar (back / forward / reload-stop),
  address bar, zoom label + buttons, open in default browser, DevTools; Chromium handlers: address / title change
  → store + `updateFilePresentation`, loading state → button state + re-apply zoom, `onBeforePopup` → new tab,
  context menu *View Page Source* → `view-source:<url>` in a new tab (CEF's own handling writes a temp file for the
  OS to open, which does nothing in the IDE). Chromium reports a `view-source:` page's address without the prefix:
  `sourceView` (set by the URL loaded) puts it back for the address bar and the store, so the tab reopens as source.
  Shortcuts are registered on the editor's root component (they win over the keymap inside the tab only).
  Find in page (⌘F, ⌘G / ⇧⌘G, F3): a find bar under the toolbar; `CefBrowser.find` highlights and steps, but JCEF
  has no find-result handler, so the match count comes from `core/Js.countMatches` run in the page (body
  `innerText`, iframes not counted). Console errors: `onConsoleMessage` (ERROR / FATAL) → `core/ConsoleLog` → red
  badge left of the zoom label (tooltip = last 5, click = DevTools), cleared by `onLoadStart` of the main frame.
  Device width: the toolbar dropdown (`deviceWidthGroup`, icon `icons/deviceWidth.svg`) sets
  `BrowserStore.width` (CSS px, absent = full); the browser component sits in a `Viewport` panel that centres it at
  `core/DeviceWidths.frameWidth` (width × zoom, clamped to the tab) — a plain resize, so the page's media queries and
  `innerWidth` see the preset; `setZoom` re-lays it out. A "390 px" label left of the zoom label resets it.
- Reload on save: `ReloadOnSave` (application `FileDocumentManagerListener.afterDocumentSaved`, setting
  `BrowserStore.reloadOnSave`, default on) collects saves for 300 ms, keeps those in a project's content and calls
  `BrowserEditor.reloadAfterSave`: a local page (`Urls.isLocal`) reloads, a `file:` page when the saved file is in
  its folder; a hidden tab reloads in `selectNotify`. Pages with a dev-server client (`LIVE_RELOAD_JS` after load:
  Vite `/@vite/client`, webpack `webpackHotUpdate*`, LiveReload, Browsersync) skip front-end files
  (`core/LiveReload`). IDE saves only, deliberately not VFS changes: frameworks write caches / compiled templates
  on every request, which would loop.
- `BrowserTabTitleProvider` — tab text = page title (40 chars) or host; tooltip = URL.
- `BrowserTabs` — open a URL as a new tab (`OpenFileDescriptor.navigate`); `openOrReuse` / `openDevServerPort`
  for the automatic dev-server tabs (reuse the tab on the same host:port; loopback names count as `localhost`).
- Dev-server auto-detect, one setting (`BrowserConfigurable`, `BrowserStore.autoOpenDevServer`), two sources:
  `DevServerWatcher` (`ExecutionListener`: a run configuration's console prints a localhost URL) and
  `DevServerPoller` (app service, started by the `DevServerPollerStarter` post-startup activity): every 2 s reads
  the OS's listening TCP ports (`/proc/net/tcp*` on Linux, `netstat` on macOS / Windows — never connects, `php -S`
  logs every connection) and opens `http://localhost:<port>/` when a port in `core/DevPorts.WATCHED` starts
  listening, in the last focused project window. This is how `npm run dev` in the **Terminal** is covered: the
  Terminal's own output API (`TerminalTabsManagerListener`, `TerminalOutputModel`) is `@ApiStatus.Experimental`
  in 2026.3, so it is deliberately not used. A run configuration triggers both; the poller skips a host:port
  auto-opened in the last 10 s.
- `core/DevPorts` — **pure JDK**: the watched port list, parsers for BSD / Windows `netstat` and `/proc/net/tcp`,
  and `Watch` (first look = baseline, so servers already running at IDE start don't open tabs).
- `actions/NewTabAction` (Tools menu, editor tab right-click, main toolbar `MainToolbarRight` with a globe icon,
  browser toolbar with a "+" (place `BrowserEditor.TOOLBAR_PLACE`), `control alt shift T` in
  `plugin.xml` — macOS's default keymap translates that to `⌥⇧⌘T`, not `⌃⌥⇧T`): opens a blank tab with the
  address bar focused. `actions/OpenUrlAction` (Tools menu + editor tab right-click, `control alt shift U` →
  `⌥⇧⌘U` on macOS), `actions/OpenSelectionAction`
  (editor right-click, only visible when the selection looks like an address; strips quotes),
  `actions/OpenFileAction` (*Open In › Browser Tab*: added to the platform's `RevealGroup`, which the project view,
  editor and editor tab menus all show, and to `OpenInBrowserEditorContextBarGroup`, the HTML editor's floating
  browser bar; visible for local `.html` / `.htm` / `.xhtml` files): saves the document, then
  `BrowserTabs.openFile` opens its `file:` URL, or focuses + reloads the tab already on that file
  (`BrowserStore.findTabByFile`, query / fragment ignored).
- `src/main/resources/META-INF/plugin.xml` — `<depends>com.intellij.modules.jcef</depends>` is required since 2026.2
  (JCEF moved out of the platform into the bundled *Web Browser (JCEF)* plugin).

## Build and test

```
./build.sh               # CoreTests + zip; PHPSTORM_CONTENTS defaults to ~/Applications/PhpStorm.app/Contents
tools/api-check.sh       # must end with "problems: none"
tools/sandbox/e2e.sh     # macOS: sandboxed PhpStorm (own config under /tmp/ebdemo) + local test pages, all checks
```

- javac must be 25 (PhpStorm's jars are Java 25 class files); output `--release 21`. `JAVA_HOME` defaults to
  PhpStorm's bundled JBR (no `jar` tool: `build.sh` falls back to `zip`).
- Classpath = `lib/` + the JCEF plugin's `lib/` (`plugins/*jcef*/lib`, `jcef-plugin` on macOS 2026.3).
- Sandbox (`tools/sandbox/`): `demo.sh` starts PhpStorm with its own config/system/plugins and serves
  `/tmp/ebdemo/site` on `127.0.0.1:8765` (`EBDEMO_PORT`); `cmd.sh <command>` talks to the helper plugin
  (`helper/Commands.java`: `open`, `tabs`, `state`, `js`, `click x y`, `rclick x y` (Chromium's context menu),
  `menu [label]` (list / pick an item of the open list popup), `dump`, `stroke "meta L"`, `action <id>`, `type`,
  `key`, `file`, `select`, `shot`, `quit`; `demo.sh --probe` reloads it without a restart). JCEF runs off-screen
  (OSR) there, so `shot` (Swing paint) includes the page. A script `click()` on a `target=_blank` link is blocked
  as a popup without a user gesture: use `click x y` (real mouse event) for that.
  Without keyboard focus (`focus null`: another app in front) use the focus-free commands: `call <method>` (a
  no-argument method of the selected `BrowserEditor`, e.g. `openFind`), `settext <field> <text>`, `save <path>`
  (edit + save a project file, as ⌘S), `focustab <title>`, `reloadinfo`, `closedialogs`. A dialog left open by a
  failed keyboard check holds back every later `invokeLater` (default modality), so unrelated checks fail after it:
  `closedialogs` first. `/tmp` is a symlink on macOS: only the `/private/tmp/…` VirtualFile is in the project's
  content. Run one sandbox at a time (they steal focus from each other, and each one's dev-port poller opens
  the others' port-4321 tabs), and build in a copy: `build.sh` wipes `build/`.
- CI: `tests.yml` runs `CoreTests` on push (macOS + Linux). `release.yml` (Actions › Release › Run workflow, or a
  `v*` tag) downloads PhpStorm `PHPSTORM_VERSION` for Linux, runs `build.sh` + `api-check.sh`, publishes the zip.
- Release: bump `<version>` + `<change-notes>` in `plugin.xml`, `tools/release.sh`, commit, push, run the workflow.
- The URLs in `README.md`, `updatePlugins.xml`, `plugin.xml`, `.github/` assume
  `github.com/Tygovanommen/editor-browser`; change them together if the repo moves.

## Rules for changes

- Chromium handler callbacks run on CEF's thread: only store writes there, UI via `invokeLater`.
- Prefer stable APIs; run `tools/api-check.sh` after every change.
- Keep `editorBrowser.xml` backward compatible (old tab ids must keep reopening).
- Add a `CoreTests` case for any change in `core/`; extend `e2e.sh` for new user-facing behaviour.