# Project: my own fork of CleanYourTV for a Philips Ambilight TV

## Purpose
Fork of Gathin23/cleanyourtv tuned for one TV: a Philips 55PUS6412/12. Goals: a faster TV
(lighter home screen, fewer background services, no ads/telemetry) and my own launcher with a few
upgrades (Ambilight panel, Croatian strings). Ambilight (follow video), Google Play Store, HDMI
inputs and the tuner must keep working. Everything stays reversible: ADB + `pm disable-user` only.

## Context
- TV: Philips 55PUS6412/12 (2017, 6400 series, 3-sided Ambilight), chassis TPM17.1E LA.
- Firmware: TPM171E_R.107.001.143.000 (built Nov 2021) — the last official release, no further OS updates exist.
- Build fingerprint: Philips/PH7M_EU_5596/PH7M_EU_5596:8.0.0/OC/4718:user/release-keys
  → Android TV 8.0 Oreo (API 26), MediaTek SoC. Entry-level hardware, likely low RAM — keep everything lightweight.
- TV IP on my LAN: 192.168.1.27 (reserved via DHCP). USB/Network debugging is enabled in Developer options.
- TV name "PHILIPS DOMA", menu language Croatian, country Croatia (from JointSpace /6/system).
- JointSpace API: v6.1.0 on http://192.168.1.27:1925 and https://192.168.1.27:1926 (self-signed).
  `/6/system` is open; `/6/ambilight/*` answers 401 until paired (pairing_type digest_auth_pairing).
  Ambilight features advertised: LoungeLight, Hue, Ambilight. os_type MSAF_2018_O.
- My GitHub: mihaelrodek (https://github.com/mihaelrodek).
- I'm on Windows, I code mainly in Java (backend) and React. Use the PowerShell variants of the repo scripts where they exist.
  (This clone was set up on macOS; the .sh variants work here, .ps1 on Windows. Both are listed below.)
- Upstream: https://github.com/Gathin23/cleanyourtv (MIT). It already has tested lists for the SAME build
  (PH7M_EU_5596, TPM171E): Philips 55PUS7503/12 and 65PUS8102/12 in examples/ — start from those.
  My model is a lower series, so some packages may be missing or different; verify against what is actually installed.

## Hard rules
- No root, no flashing, no partition changes, no serial/U-Boot experiments. ADB + `pm disable-user` only.
- Never uninstall packages; disable only, so everything stays reversible.
- Before any change, run the repo's backup/record step (`tools/disable.sh` calls `tools/recovery.py --save`
  before the first command reaches the TV; records live in `~/.cleanyourtv/`). Also keep a raw dump in
  `backup/` (gitignored): `pm list packages -f`, `pm list packages -d`, `getprop`, `settings list` x3.
  Apply debloat in small batches and verify after each
  (pull a screenshot from the TV, check Home, Ambilight follow-video, Play Store, HDMI inputs, TV channels).
- Never disable: Google Play Store / Play Services, anything related to Ambilight, picture/sound settings,
  TV settings app, inputs/tuner/channel apps, the stock launcher before the new launcher is confirmed working as Home.
  Concretely on this platform (from the upstream Philips lists): org.droidtv.playtv, org.droidtv.xtv,
  org.droidtv.GlobalKey, org.droidtv.settings, org.droidtv.tvsystemui, com.mediatek.tvinput,
  com.mediatek.tunerservice, org.droidtv.nettvbrowser (HbbTV red button), org.droidtv.eum (hide, don't disable),
  com.google.android.apps.mediashell (Chromecast), firmware update services, Ambilight/Hue packages.
- Ask me before disabling anything you're not sure about, and before every step that changes the TV.
- Keep the launcher compatible with minSdk 26 (Android 8.0). Check the upstream launcher's minSdk
  and any APIs used above 26; fix or guard them. Keep reduced_motion / low-memory settings in mind for this hardware.
- Private DNS needs Android 9+, so skip that feature; ad blocking will be done at network level, not on the TV.

## Environment (set up 2026-10-20)
- Python venv: `tools/venv` (the scripts look for exactly this name). Python 3.9, adb-shell 0.4.4.
- JDK 17: sdkman `~/.sdkman/candidates/java/17.0.20-tem` (default java stays 21). Set `JAVA_HOME` to it for Gradle.
- Android SDK: `~/Library/Android/sdk` with platforms;android-34 and build-tools;34.0.0 (AGP 8.5.2, Gradle 8.9).
  sdkmanager from Homebrew cask android-commandlinetools: `/opt/homebrew/share/android-commandlinetools/cmdline-tools/latest/bin/sdkmanager`.
- `gh` installed via Homebrew; needs `gh auth login` once.
- On Windows: JDK 17 (Temurin), Android Studio SDK at %LOCALAPPDATA%\Android\Sdk, `python -m venv tools\venv`,
  `tools\venv\Scripts\pip install -r tools\requirements.txt`.

## Commands
Always from the repo root. TV_HOST=192.168.1.27.

Probe / shell:
```
# macOS/Linux
TV_HOST=192.168.1.27 tools/venv/bin/python -I tools/tv.py --probe
TV_HOST=192.168.1.27 tools/venv/bin/python -I tools/tv.py 'pm list packages'
# Windows PowerShell
$env:TV_HOST='192.168.1.27'; tools\venv\Scripts\python -I tools\tv.py --probe
```
Backup dump (before the first change):
```
mkdir -p backup && for c in 'pm list packages -f' 'pm list packages -d' 'getprop' 'settings list system' 'settings list secure' 'settings list global' 'dumpsys package' ; do TV_HOST=192.168.1.27 tools/venv/bin/python -I tools/tv.py "$c" > "backup/$(echo $c | tr ' ' _).txt"; done
```
Debloat / restore:
```
TV_HOST=192.168.1.27 tools/disable.sh examples/philips-55pus6412/debloat.txt
TV_HOST=192.168.1.27 tools/restore.sh examples/philips-55pus6412/debloat.txt com.google.android.tvlauncher
# Windows
tools\disable.ps1 -TvHost 192.168.1.27 examples\philips-55pus6412\debloat.txt
tools\restore.ps1 -TvHost 192.168.1.27 examples\philips-55pus6412\debloat.txt com.google.android.tvlauncher
```
Launcher build + install as Home (builds release APK with debug signing, pushes it, installs, starts it,
then disables the stock launcher and sets Home; rolls back if Home does not land on com.gathin.tvhome):
```
export JAVA_HOME=~/.sdkman/candidates/java/17.0.20-tem
TV_HOST=192.168.1.27 launcher/build-and-install.sh
# Windows
launcher\build-and-install.ps1 -TvHost 192.168.1.27
```
Build only (no TV): `cd launcher && JAVA_HOME=... ./gradlew assembleRelease`. Unit tests: `./gradlew test`.
Screenshot from the TV:
```
TV_HOST=192.168.1.27 tools/venv/bin/python -I tools/tv.py 'screencap -p /sdcard/s.png' && TV_HOST=192.168.1.27 tools/venv/bin/python -I tools/tv.py --pull /sdcard/s.png backup/s.png
```
Repo tests (host side, no TV): `python3 -m pytest tests/`.

## How to restore the TV
1. `tools/restore.sh examples/philips-55pus6412/debloat.txt com.google.android.tvlauncher` re-enables every
   package the list disabled (using the record from `~/.cleanyourtv/`, so packages that were already
   disabled stay that way), gives Home back to the stock launcher and restores animation scales.
2. If the new launcher is Home and misbehaves: `pm enable --user 0 com.google.android.tvlauncher` then
   `cmd package set-home-activity --user 0 com.google.android.tvlauncher/<HOME activity>` (build-and-install.sh
   writes the exact commands to `~/cleanyourtv-handover-192.168.1.27.txt` when a handover fails).
3. To remove the launcher: `pm uninstall com.gathin.tvhome` (it is the only thing ever installed).
4. Nothing is ever uninstalled from stock, so a factory reset is never required for undo.

## Repo structure
- `tools/` — ADB-over-TCP client (`tv.py`, adb-shell, no adb binary), `disable.sh/.ps1`, `restore.sh/.ps1`,
  `recovery.py` (state record for undo), `b64push.py` (large file push), `theme.sh/.ps1`, `adb-closed.py`.
- `examples/<model>/debloat.txt` — one tested list per TV; header documents platform, what is kept and why.
  Ours: `examples/philips-55pus6412/`. Reference: `examples/philips-55pus7503/`, `examples/philips-65pus8102/`.
- `launcher/` — Android app `com.gathin.tvhome` (Java, minSdk 26, targetSdk 34, R8 minified, ~390 KB).
  Config in `app/src/main/res/values/config.xml`; translations in `values-<lang>/strings.xml`.
- `themes/` — JSON themes pushed with `tools/theme.sh` without rebuild.
- `docs/` — modules/cards spec, remote access, troubleshooting, screenshots. `tests/` — host-side pytest for the tools.
- `PROMPT.md` — upstream's generic assistant prompt; this CLAUDE.md replaces it for this fork.

## Verified on this TV (tick as done)
- [x] ADB connection over 5555 authorised (2026-10-20; dialog only appeared after a mains power cycle while MAXtv owned Home)
- [x] Backup dump taken (backup/20261020-1943-stock), recovery record written
- [x] Debloat batches 1–3 applied (27 packages), no crashes/ANR, Home, tuner, HDMI, Play Store, JointSpace alive
- [ ] Debloat batch 4 (recommendation rows) applied after the new launcher owns Home
- [x] Launcher built with JDK 17 / SDK 34 (395 KB, unit tests pass)
- [x] Launcher installed (2026-10-20) next to MAXtv, which stays Home by choice; remote TV key opens it (confirmed with the real remote)
- [x] Animation scales set to 0.5 (2026-10-20)
- [x] JointSpace pairing from PC works (tools/jointspace.py); power, mode, currentconfiguration, topology (left 4, right 4, top 0), supportedstyles answer
- [ ] Ambilight panel in launcher works on the TV
- [~] Croatian strings: Ambilight panel + Home greetings translated (values-hr); ~80 general strings still pending
- [x] Quick-launch overlay on the remote "+" key works (after the Android 8.0 translucent-orientation fix)
- [x] Remote TV key + quick-launch share InputKeyService; a translucent-activity crash can wedge the remote, fixed
- [ ] Restore script tested end to end (restore, stock state confirmed, re-applied)
- [ ] Soak test across standby and reboot
- [ ] USB/Network debugging turned off at the end

## Tasks
1. Fork Gathin23/cleanyourtv to my account (mihaelrodek) with `gh repo fork`, clone it, add upstream as a remote.
2. Create CLAUDE.md with: project purpose, TV specs above, the hard rules, build/install commands for Windows,
   how to restore the TV, repo structure notes, and a "verified on this TV" checklist.
3. Set up the environment (Python venv for tools/, JDK 17, Android SDK platform 34 + build-tools) and check
   `tools/tv.py --probe` reaches the TV at 192.168.1.27. Report model, Android version, RAM, free storage
   and the current Home launcher.
4. Build my debloat list in examples/philips-55pus6412/ starting from the two PH7M_EU_5596 lists. Diff them against
   `pm list packages` on my TV and show me the full list with what each package does before applying anything.
5. Apply debloat in batches, verify after each, then install the launcher and make it Home.
6. Speed tweaks: set animation scales (ask me 0.5 or off), report and help free storage if it's low.
7. Launcher upgrades (propose a plan first, then implement one at a time):
   a. Ambilight tile/panel: on/off, switch mode (follow video / follow audio / lounge / static colour),
      brightness. Use the TV's local JointSpace API at https://127.0.0.1:1926/6/ (self-signed cert, trust it
      only for 127.0.0.1; HTTP digest auth). Implement the one-time pairing flow (/6/pair/request, /6/pair/grant,
      PIN shown on TV) using eslavnov/pylips as the reference, and store credentials in app-private storage.
      First verify the endpoints work on this firmware (/6/ambilight/power, /6/ambilight/currentconfiguration,
      /6/ambilight/topology) from the PC against https://192.168.1.27:1926 before writing app code.
      If the API isn't available on this model, tell me.
   b. Croatian translation of the launcher strings.
   c. Anything else you think fits after reading the code; list it, don't build it unasked.
8. Test the restore script end to end once (restore, confirm stock state, re-apply).
9. At the end: contribute-ready debloat list for 55PUS6412 in the upstream format, a short README section for my fork,
   small logical commits pushed to mihaelrodek/cleanyourtv, and remind me to turn off USB/Network debugging on the TV.

Start with tasks 1–3 and stop to show me the results before touching the TV.
