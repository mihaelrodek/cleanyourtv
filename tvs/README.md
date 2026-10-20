# My TVs

One profile per physical TV, so the same launcher and tools serve all of them without redoing the
work. What is **shared** across every TV and what is **per-TV**:

| Shared (built/written once) | Per-TV |
|---|---|
| `launcher/` — one APK, minSdk 26, runs on Android 8 and 12 | IP address |
| `tools/` — ADB + JointSpace helpers | Model + platform (board, fingerprint) |
| The general debloat approach and the keep-list rules | `examples/<model>/debloat.txt` (packages differ by platform) |
| JointSpace pairing flow (`tools/jointspace.py`) | `themes/<name>/config.json` (dock apps, remote keys, panels) |
| | Pairing credentials in `~/.cleanyourtv/jointspace-<ip>.json` |
| | Capabilities (e.g. Private DNS needs Android 9+) |

## How to work on a TV

```sh
# From the repo root, load the TV you are working on, then use the normal commands.
source tvs/tv1-dnevni/tv.env          # sets TV_HOST and the paths below
tools/venv/bin/python -I tools/tv.py --probe
tools/disable.sh "$TV_DEBLOAT"
JAVA_HOME=~/.sdkman/candidates/java/17.0.20-tem TV_HOST=$TV_HOST launcher/build-and-install.sh STOCK_LAUNCHER=$TV_STOCK_LAUNCHER
tools/theme.sh "$TV_THEME"
```

The launcher APK is identical for every TV; only the theme (`$TV_THEME`) and the debloat list
(`$TV_DEBLOAT`) change. Build the APK once and install the same file on each.

## The TVs

| Profile | Model | Platform | Android | Home | Notes |
|---|---|---|---|---|---|
| `tv1-dnevni` | 55PUS6412/12 | TPM171E / PH7M_EU_5596, MT5891 | 8.0 | MAXtv kept | done; see its notes.md |
| `tv2-tpm191e` | TBD (PH9M_EA_5599) | TPM191E / PH9M_EA_5599, MT5887 | 12 | TBD | upstream `examples/philips-tpm191e` matches its fingerprint; verify when connected |
| `tv3-*` | TBD | TBD | TBD | TBD | data expected the day after 2026-10-20 |

## Adding a TV

1. Get it on the LAN, enable USB/Network debugging, note its IP.
2. `TV_HOST=<ip> tools/venv/bin/python -I tools/tv.py --probe` — read model, board, fingerprint, Android.
3. Pick or create `examples/<model>/debloat.txt`: if the fingerprint matches an existing list
   (upstream or ours), start from it and diff against `pm list packages` on the TV; else start from
   the nearest same-platform list.
4. Copy `themes/philips-55pus6412/config.json` to `themes/<name>/` and adjust `dock_apps`,
   `live_tv_package`, the remote-key codes and which panels to show.
5. Copy a `tv.env` here, fill in the paths, and follow "How to work on a TV".
