# Contributing

Bug reports, ideas and pull requests are welcome. For bigger changes, open an issue or discussion first.

## Build

You only need PhpStorm 2026.2+ installed (no Gradle).

```
./build.sh              # tests + build/distributions/editor-browser-<version>.zip
tools/api-check.sh      # must end with "problems: none"
tools/sandbox/e2e.sh    # macOS: the plugin in a sandboxed PhpStorm, every feature checked
```

PhpStorm somewhere else? `PHPSTORM_CONTENTS=/path/to/PhpStorm.app/Contents ./build.sh`.

## Rules

See [`CLAUDE.md`](CLAUDE.md) for the code map. In short:

- `core/` is plain Java without IntelliJ imports; every change there gets a test in `CoreTests`.
- Only stable PhpStorm APIs.
- Chromium callbacks run on its own thread: touch Swing only through `invokeLater`.
