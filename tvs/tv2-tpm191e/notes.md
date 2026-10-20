# TV 2 — Philips TPM191E (PH9M_EA_5599, Android 12)

From the Current Software screen (photo, 2026-10-20):
- Version TPM191E_R.211.000.103.000, build STT2.230526.001, Android 12.
- Fingerprint: Philips/PH9M_EA_5599/PH9M_EA_5599:12/STT2.230526.001/4215:user/release-keys.
- About 2 GB RAM, armeabi-v7a, MediaTek MT5887.

This fingerprint matches **upstream `examples/philips-tpm191e/debloat.txt`** exactly, so start the
debloat from that list and verify it against this TV's own `pm list packages` before applying.

Differences from TV 1 to handle when setting up:
- Android 12, so **Private DNS (DoT) is available** — an on-device ad-block/DNS option TV 1 could not use.
- Different package set (Android 12 Philips): expect org.droidtv.* names but not identical to TPM171E.
- Check the remote's TV/Input keycodes and the Ambilight JointSpace node IDs on THIS TV before reusing
  TV 1's theme values (they can differ by platform). JointSpace pairing is the same flow.
- Launcher APK is the same file as TV 1; only themes/philips-tpm191e/config.json differs (dock apps,
  live_tv_package, remote keys, which panels).

## To do when connected
- [ ] Probe, record model + RAM + storage + Home launcher
- [ ] Backup + recovery record
- [ ] Verify examples/philips-tpm191e list against this TV; adjust; apply in batches
- [ ] Create themes/philips-tpm191e from TV 1's theme; set dock_apps / live_tv_package / keycodes
- [ ] JointSpace pair; read Ambilight + picture-style node IDs on this TV
- [ ] Install launcher; decide Home; verify
