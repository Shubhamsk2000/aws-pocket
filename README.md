# Pocket: your AWS server on your phone

An Android app that connects to the EC2 instance over **SSH**, the same way `scripts/tunnel.sh` does,
and forwards its web apps to the phone. Nothing new is opened on the server: the security group keeps only port 22.

## Install

1. Copy `dist/Pocket-1.0.0.apk` to the phone, from your laptop:
   `scp -i ~/.ssh/studio.pem ubuntu@<ip>:~/dev_projects/aws-pocket/dist/Pocket-1.0.0.apk .`
   then send it to the phone (USB, Drive, Telegram to yourself…), tap it and allow "install unknown apps".
2. Open Pocket → **Add server**: host = the instance's public IP, user `ubuntu`, **Import .pem** (your `studio.pem`).
   Studio port stays `8000`.
3. The security group's SSH rule must allow the phone's network. *My IP* only covers your home Wi-Fi;
   on mobile data the IP changes. Either add the phone's IP when needed, or open 22 wider. The key is still required.

## What's in it

| Tab | |
|---|---|
| **Home** | Live CPU and memory graphs, disks, swap, network speed, the running Shorts job, your **pinned apps** |
| **Apps** | Every listening port, found automatically: process, page title, public or local. **Pin** the ones you want on Home, rename them, set a start path, **hide** the rest, add a port by hand (it lights up when the app starts). Tap one to open it in the built-in browser (uploads and downloads work), or in Chrome |
| **Shorts** | Paste a link (+ optional focus note, *These first* / *Only these*) → **Make shorts**. Every project with its live stage progress, ETA, stop, retry and resume. Tap one to open its Studio page |
| **Tools** | Processes (CPU/RAM, terminate), systemd services + Docker containers with **live logs** and restart/stop/start, **run a command** with one-tap snippets, extra **port forwards** for other apps, reboot / shut down |

Also:
- **Share → "Make shorts"** from the YouTube app starts a job without opening Pocket (it connects if needed).
- **Background tunnel** with a notification that shows the running job's progress; notifies when a job finishes or fails.
- **Auto-reconnect** on Wi-Fi ↔ mobile switches; open pages keep their local port, so they carry on working.
- **Optional AWS control** (server settings): start and stop the instance from the phone. After a start, Pocket picks up
  the **new public IP** by itself. Use an IAM user that can only call `ec2:DescribeInstances`, `ec2:StartInstances`, `ec2:StopInstances`.
- Security: keys are encrypted with the Android Keystore, the host key is pinned on first use
  (and you're warned if it changes), and the app makes no other network calls.

## How it works

- `android/`: Kotlin + Jetpack Compose, sshj for SSH. Each app's port is forwarded to `127.0.0.1:<20000+port>` on the phone.
- `android/app/src/main/assets/probe.py`: a stdlib-only Python script. The app uploads it to
  `~/.cache/aws-pocket/` on first connect and runs it over SSH for stats, ports, processes, services and logs.
  There is no server-side install and no daemon.

## Build

```bash
./build.sh            # -> dist/Pocket-<version>.apk
```

Needs JDK 17 and the Android SDK at `~/android-sdk` (already set up on this instance). Bump `versionCode` /
`versionName` in `android/app/build.gradle.kts` for an update. **Keep `signing/` and `android/keystore.properties`
safe (copy them to your laptop).** Without them, an update can't be installed over the old app.
