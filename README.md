# Editor Browser

[![Latest release](https://img.shields.io/github/v/release/Tygovanommen/editor-browser?label=download)](https://github.com/Tygovanommen/editor-browser/releases/latest)
[![Tests](https://github.com/Tygovanommen/editor-browser/actions/workflows/tests.yml/badge.svg)](https://github.com/Tygovanommen/editor-browser/actions/workflows/tests.yml)
![PhpStorm 2026.2+](https://img.shields.io/badge/PhpStorm-2026.2%2B-7B5CF5)
[![MIT license](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

**Websites as editor tabs, right next to your files.**

Checking a change usually means switching to the browser and back. This plugin opens any site
(`localhost:3000`, `backend.test/admin`, Mailpit, docs) as a normal PhpStorm editor tab, so it sits between your
file tree and whatever else you have open. Split it next to the code, keep several sites open as tabs, and they're
still there after a restart.

## Features

- **A tab per site:** *Tools › Open URL in Editor Tab…* (also on right-clicking an editor tab), with your recent
  addresses in the dropdown.
- **Quick blank tab:** the globe button in the main toolbar (next to Run), ⌥⇧⌘T, or *Tools › New Browser Tab* (also a
  right-click option on editor tabs) opens an empty tab with the address bar focused, ready to type into — no dialog.
- **Splits like a file:** right-click the tab › *Split Right* for code and site side by side, or two sites at once.
- **Browser basics in every tab:** back, forward, reload, an address bar, zoom (handy in a narrow split: 80% shows
  the desktop layout) and Chrome DevTools.
- **You can see what a page is doing:** while it loads, a progress line runs under the toolbar, the tab icon
  spins and Reload becomes Stop (X). A server that hasn't answered after a few seconds gets a "Waiting for
  localhost:8000" bar (paused at a breakpoint?). A page that can't load gets a red bar saying why, in words
  (nothing running on that port, unknown host, connection closed, …), with *Try Again*, and a red tab icon. When a
  local server refused the connection (a dev server still starting), the tab waits for it and reloads by itself
  once it is up. An HTTP error answer shows as a 404 / 500 badge in the toolbar, and a crashed page gets a bar
  with *Reload*.
- **A toolbar you arrange:** the toolbar keeps to back / forward / reload, the address bar, New Tab, DevTools and a
  ⋮ menu with the rest (device width, color scheme, zoom, find, share preview, clear site data, open in default
  browser). Under
  Settings › Tools › Editor Browser (or ⋮ › *Customize Toolbar…*) put each item on the toolbar, in ⋮ or hide it;
  shortcuts keep working for hidden items.
- **Device widths:** ⋮ › *Device Width* lays the page out at 390 (phone), 768 (tablet) or 1280 px
  (laptop) wide, centred in the tab, so you see its responsive layout without resizing anything. The width is in
  CSS px, so it scales with the zoom; in a tab narrower than that, the page gets the tab's width. Click the
  "390 px" label (or pick *Full Width*) to go back.
- **Light / dark mode:** ⋮ › *Color Scheme* › *Light* or *Dark* makes the page see that
  `prefers-color-scheme`, to check a site's dark mode without switching your OS; per tab, kept after a restart.
  Click the "Dark" label (or pick *System*) to go back.
- **Clear site data:** ⋮ › *Clear Site Data* deletes the page's cookies and storage (local / session storage,
  IndexedDB, service workers, Cache Storage), empties the browser cache and reloads: a fresh first visit, e.g. to
  test a login or a cookie banner again.
- **Password-protected sites:** a site that asks for a username and password (HTTP basic auth, e.g. a staging
  server) gets a Sign In dialog; the login is used for that site until PhpStorm restarts, or ticked *Remember*, kept
  in the IDE's password store. (Digest authentication isn't supported.)
- **Reloads on save:** save a file in the project and the tabs on a local page (`localhost`, an IP, `*.test`,
  `*.local`) reload; a tab in the background reloads when you switch to it. Pages whose dev server already reloads
  them (Vite, webpack HMR, LiveReload, Browsersync) are left to it for front-end files (JS, CSS, Vue, …) and only
  reload for server-side ones (PHP, Twig, Blade, …). Only saves in the IDE count. Toggle under
  Settings › Tools › Editor Browser.
- **Find in page:** ⌘F opens a find bar with the number of matches; Enter / ⇧Enter (or ⌘G / ⇧⌘G) step through
  them, Esc closes it.
- **Console errors at a glance:** a red badge in the tab's toolbar counts the page's JavaScript console errors;
  hover for the last messages, click to open DevTools.
- **Share preview:** ⋮ › *Share Preview* (or put its button on the toolbar) shows the link card the page gets when
  its URL is pasted into Slack, X, LinkedIn or WhatsApp (built from its `og:` / `twitter:` meta tags: image, site,
  title, description), plus what is missing or too long, e.g. no `og:image`, a relative image URL, a too-small
  image.
- **New windows become tabs:** links with `target="_blank"` and `window.open()` open as a new editor tab.
- **Page source:** right-click the page › *View Page Source* opens the HTML in a new tab (`view-source:` addresses
  work in the address bar too).
- **Remembers:** tabs come back after a restart on the page you left them, with their zoom, device width and color
  scheme.
- **From code:** select a URL › right-click › *Open in Editor Tab*.
- **HTML files of the project:** right-click an `.html` file (in the project view, the editor or on its tab) ›
  *Open In › Browser Tab*, or click the globe in the HTML editor's floating browser bar. The file is shown from disk
  (`file://`), so relative CSS, scripts and images load. Running it again focuses and reloads that tab, and saving
  the page or a file in its folder reloads it.
- **PDF files in a tab:** double-click a PDF in the project (or open it from *Go to File*, a link, …) and it opens
  as an editor tab in Chromium's PDF viewer instead of your system's PDF app: page thumbnails, zoom (the viewer's
  − / + and fit buttons), rotate, print and download, and ⌘F to find text in it (matches are highlighted and stepped
  through; there is no match count for a PDF). The tab is the PDF file itself, so it splits and comes back after a
  restart like any file, and it reloads when the PDF changes on disk (e.g. a report you generate again). A link to a
  PDF in any browser tab shows it in the same viewer. Turn it off under Settings › Tools › Editor Browser to have
  PDFs open in the system's app again.
- **Looks like a tab:** the page's own favicon becomes the tab icon.
- **Dev servers open themselves:** start one and it opens as a tab, reusing the tab on a restart. From a run
  configuration, the `localhost` URL it prints is used. From the Terminal (or anywhere else), a common dev-server
  port starting to listen is noticed within a couple of seconds: 1313, 3000, 3001, 3333, 4000, 4173, 4200, 4321,
  5173, 5174, 6006, 8000, 8080, 8081. The tab opens in the project window you used last. Toggle under
  Settings › Tools › Editor Browser.
- Uses PhpStorm's own embedded Chromium (JCEF): nothing extra to download. Logins are shared by all tabs.

## Install

1. Download the zip from the [latest release](https://github.com/Tygovanommen/editor-browser/releases/latest).
2. PhpStorm › Settings › Plugins › ⚙ › **Install Plugin from Disk…** › pick the zip › restart.

To get updates, add this under Settings › Plugins › ⚙ › **Manage Plugin Repositories…**:
`https://raw.githubusercontent.com/Tygovanommen/editor-browser/main/updatePlugins.xml`

Requires PhpStorm 2026.2 or newer (any JetBrains IDE on build 262+ with the bundled *Web Browser (JCEF)* plugin).

## Shortcuts

Inside a browser tab (⌘ on macOS, Ctrl elsewhere):

| Shortcut       | Does                    |
|----------------|-------------------------|
| ⌘L             | Focus the address bar   |
| ⌘R / F5        | Reload (⌘⇧R: no cache)  |
| ⌘[ / ⌘]        | Back / forward          |
| ⌘= / ⌘- / ⌘0   | Zoom in / out / reset   |
| F12 / ⌥⌘I      | DevTools                |
| ⌘F             | Find in page            |
| ⌘G / ⇧⌘G, F3   | Next / previous match   |

Global, from anywhere in the IDE:

| macOS | Windows / Linux  | Does                    |
|-------|------------------|-------------------------|
| ⌥⇧⌘T  | Ctrl+Alt+Shift+T | New Browser Tab (blank) |
| ⌥⇧⌘U  | Ctrl+Alt+Shift+U | Open URL in Editor Tab… |

Reassign either one under Settings › Keymap if it clashes with something else.

The address bar takes what you'd type in a browser: `localhost:3000` and `*.test` / `*.local` / IP addresses get
`http://`, anything else `https://`.

## Feedback

- ⭐ **Star the repo** if it saves you time.
- 🐞 **Report a bug** in [Issues](https://github.com/Tygovanommen/editor-browser/issues).
- 💬 **Ask a question or share an idea** in [Discussions](https://github.com/Tygovanommen/editor-browser/discussions).

## Contributing

Run `./build.sh` to test and build (only needs PhpStorm installed). See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

[MIT](LICENSE). Made by [Netonic](https://netonic.nl).
