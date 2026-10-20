# TV 1 — dnevni (Philips 55PUS6412/12)

- IP 192.168.1.27 (DHCP reserved). 1.9 GB RAM, armeabi-v7a, final firmware TPM171E_R.107.001.143.000.
- Home is MAXtv (hr.telekom.android.maxtvp); the remote **TV key** opens the launcher (app_keys 170).
- JointSpace paired; creds in ~/.cleanyourtv/jointspace-192.168.1.27.json. Ambilight is 2-sided here
  (topology left 4 / right 4 / top 0). Picture-style node 2131230838; Ambilight brightness node 2131230769.
- 31 packages disabled (batches 1–5, including the TV Guide). Animation scales 0.5.
- ADB "Allow" dialog only appeared after a **mains** power cycle while MAXtv owned Home.

## Verified on this TV
- [x] ADB over 5555, backup + recovery record
- [x] Debloat batches 1–5 applied, Home/Ambilight/Play Store/HDMI/channels OK
- [x] Launcher installed beside MAXtv; TV key opens it (real remote)
- [x] Ambilight panel, TV (picture) panel, quick-launch on "+" (keycode 82)
- [x] Croatian greetings + Ambilight/TV strings; date full-width under the clock
- [ ] Wi-Fi info panel (in progress)
- [ ] Restore script tested end to end
- [ ] Full Croatian translation of the rest of the launcher
- [ ] USB/Network debugging turned off at the end
