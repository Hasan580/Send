# Contributing to Send

Thanks for helping. Send is small on purpose, so changes that keep it small, fast and simple are the easiest to merge.

## Set up

- **Windows app:** .NET 10 SDK. `dotnet build windows/Send/Send.csproj`
- **Android app:** JDK 17+, Android SDK platform 36. `cd android && ./gradlew assembleDebug`
- The two apps must be on the same network to test them against each other. `windows/Send.Cli` lets you test the PC side without the UI.

## Before you open a pull request

1. Keep the change focused: one fix or feature per pull request.
2. Match the surrounding code (naming, comment density, no extra dependencies unless there is a strong reason).
3. If you touch the protocol (`Wire.cs` / `net/Wire.kt`), keep both sides in step and say so in the description.
4. Test over the network, not just by building. Transfer a few files in both directions and compare checksums.
   `sendcli probe <phone-ip>` must still show every request refused.
5. Update the [changelog](CHANGELOG.md) under an "Unreleased" heading.

## Ideas that fit

See the roadmap in the README. Bug reports with the Windows version, Android version, router type and whether the PC is wired or on Wi-Fi are especially useful.

## Code of conduct

Be kind and assume good faith. Harassment of any kind is not welcome.
