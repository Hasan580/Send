# Changelog

All notable changes to Send are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org).

## [1.0.0] - 2026-10-27

First release.

### Added
- **Windows app** (WPF, one 0.7 MB exe): drag and drop files or folders, tray icon, optional start with Windows,
  "Show in folder" for received files, single instance.
- **Android app** (2.4 MB APK): send files, photos and text; share-sheet target; foreground service so the PC can reach the phone with the screen off.
- **In-app photo gallery** on Android: browse photos and videos, filter, select many, send. The system picker stays available as a fallback.
- **Automatic discovery and connection**: the PC tries the phone's last address, then scans the local /24 of its physical adapter.
  Connects in about 2 seconds when either app opens.
- **One-time pairing** with a 4-digit code derived from the phone's certificate (shown on both screens) and an Allow tap on the phone.
- **SEND/1 protocol** over TLS 1.3 with a certificate pinned by the PC and a 256-bit token per paired PC.
- Transfers in both directions with folder structure, Unicode names, empty files, retries and cancel; up to 3 files in parallel.
- Text snippets in both directions with a Copy button.
- `Send.Cli` test driver with a `probe` command that checks that bad tokens, bad frames and wrong certificates are refused.
- CI that builds both apps, and a security policy.

### Verified
- 500 MB file PC to phone at 11.1 MB/s, SHA-256 identical; also with the phone's screen off.
- 200-file nested folder (Unicode names, empty file) PC to phone, all hashes identical.
- 300 MB and 40 MB phone to PC, SHA-256 identical, including through the real Android share intent on the release build.
- Wrong token, unknown id, unknown operation, oversized frame and wrong pinned certificate are all refused.
- The final 1.0.0 release files (the APK and exe attached to this release) were re-tested in both directions, phone to PC through the Android share intent, with matching SHA-256.

[1.0.0]: https://github.com/Hasan580/Send/releases/tag/v1.0.0
