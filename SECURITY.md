# Security policy

## Supported versions

Only the latest release receives security fixes.

| Version | Supported |
|---|---|
| 1.0.x | Yes |

## Reporting a vulnerability

Please **do not** open a public issue with the details of a security problem.

- If this repository shows **Security**, then **Report a vulnerability**, use that: it is private to the maintainer.
- If you do not see that option, open an issue titled "Security contact request" with **no technical details**, and the maintainer will arrange a private channel.

Include the steps to reproduce, the versions of both apps, and what an attacker on the same network could do.

## Design in short

- Everything runs over TLS 1.3 on port 53420. The phone's key is generated in the Android Keystore and never leaves the device.
- The PC pins the phone's certificate when pairing. Pairing needs a tap on the phone and a matching 4-digit code on both screens.
- Each paired PC has a random 256-bit token, compared in constant time. Without it every operation except `hello` is refused.
- File and folder names are sanitised on receipt; frames are capped at 8 MB.

Out of scope: someone with physical access to an unlocked phone or PC, and malware running as you on either device.
