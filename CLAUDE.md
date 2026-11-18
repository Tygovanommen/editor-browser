# Editor Browser — context for Claude

PhpStorm plugin by Netonic: websites as normal editor tabs (next to files, in splits), using PhpStorm's bundled
embedded Chromium (JCEF). Sister project of `worktree-workspaces` (same build / release setup).
User-facing docs: `README.md` (public on GitHub, MIT).

Why it exists: PhpStorm's "Open in Editor Tab" for tool windows only works for tool windows that implement the
internal `ToolWindowInEditorSupport` (the Terminal does), so browser plugins such as GBrowser stay side panels.
This plugin makes each site a (virtual) file instead, so it is a real editor tab.

## Status (18 Nov 2026)

- Version 0.2.0, id `nl.netonic.editor-browser`, vendor Netonic, `since-build` 262 (PhpStorm 2026.2+).
  Distributed via GitHub Releases + `updatePlugins.xml` (custom plugin repository), not the Marketplace.
- Verified on PhpStorm 2026.3 (263.7307.215): `tools/api-check.sh` reports no internal / experimental / deprecated
  API use; `tools/sandbox/e2e.sh` passes 22/22 (open, title, favicon, links, back, address bar, `_blank` → tab,
  zoom shortcuts, ⌘L, New Tab, Split Right, Open URL dialog, selection action, dev-server auto-detect, tabs
  restored after restart, no log errors).
- `EditorEmptyTextProvider`/`EditorEmptyTextSink` (`impl` package) are `@ApiStatus.Internal` with no stable
  replacement; don't reintroduce an empty-editor-placeholder hint through them, `api-check.sh` will flag it.
- Not verified yet: building against 2026.2.3 in the release workflow (the Linux tarball's JCEF folder is matched
  as `plugins/*jcef*`); Windows / Linux at runtime.

## Model

- A tab is a `BrowserFile` (contentless `VirtualFile`) in `BrowserFileSystem`, protocol `editor-browser://<id>`,
  id = 12 hex chars. Being a real file system is what lets PhpStorm reopen tabs after a restart: it saves open
  files by URL and finds them again through `findFileByPath`, which only answers for ids `BrowserStore` knows.
- `BrowserStore` (app service, `editorBrowser.xml`): per id the current URL, page title and zoom %, plus the
  recent typed addresses (15). Entries are not removed when a tab closes (closing a project closes its tabs too,
  which would lose them for the restart); the oldest are pruned past 200.
- Split Right on a browser tab gives a second `BrowserEditor` (second Chromium) for the same file / id: both write
  the same store entry; the last navigation wins for the tab title and the restart.

## Code map

`src/main/java/nl/netonic/browser/`

- `core/Urls` — **pure JDK** (covered by `CoreTests`): `normalize` (typed text → URL; local hosts http, else
  https), `looksLikeUrl` (for the selection action; rejects file names like `app.vue`), `label`, `shorten`.
- `BrowserFileSystem`, `BrowserFile`, `BrowserFileType` (globe icon, registered without extensions).
- `BrowserEditorProvider` (`HIDE_DEFAULT_EDITOR`) → `BrowserEditor`: toolbar (back / forward / reload-stop),
  address bar, zoom label + buttons, open in default browser, DevTools; Chromium handlers: address / title change
  → store + `updateFilePresentation`, loading state → button state + re-apply zoom, `onBeforePopup` → new tab.
  Shortcuts are registered on the editor's root component (they win over the keymap inside the tab only).
- `BrowserTabTitleProvider` — tab text = page title (40 chars) or host; tooltip = URL.
- `BrowserTabs` — open a URL as a new tab (`OpenFileDescriptor.navigate`).
- `actions/NewTabAction` (Tools menu, editor tab right-click, browser toolbar, `⌃⌥⇧T`): opens a blank tab with the
  address bar focused. `actions/OpenUrlAction` (Tools menu + editor tab right-click, `⌃⌥⇧U`), `actions/OpenSelectionAction`
  (editor right-click, only visible when the selection looks like an address; strips quotes).
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
  (`helper/Commands.java`: `open`, `tabs`, `state`, `js`, `click x y`, `stroke "meta L"`, `action <id>`, `type`,
  `key`, `file`, `select`, `shot`, `quit`; `demo.sh --probe` reloads it without a restart). JCEF runs off-screen
  (OSR) there, so `shot` (Swing paint) includes the page. A script `click()` on a `target=_blank` link is blocked
  as a popup without a user gesture: use `click x y` (real mouse event) for that.
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

## Backlog

1. Per-ticket URLs with worktree-workspaces (optional dependency): a ticket remembers its site, switching tickets
   opens it.
2. Device width presets (e.g. 390 / 768 / 1280 px) next to zoom.
3. "Open current file in browser tab" for HTML files; a default shortcut for Open URL.
4. Find in page (⌘F inside the tab).
