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
- **Splits like a file:** right-click the tab › *Split Right* for code and site side by side, or two sites at once.
- **Browser basics in every tab:** back, forward, reload, an address bar, zoom (handy in a narrow split: 80% shows
  the desktop layout) and Chrome DevTools.
- **New windows become tabs:** links with `target="_blank"` and `window.open()` open as a new editor tab.
- **Remembers:** tabs come back after a restart on the page you left them, with their zoom.
- **From code:** select a URL › right-click › *Open in Editor Tab*.
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

*Open URL in Editor Tab…* has no default shortcut; give it one under Settings › Keymap.

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
