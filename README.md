# Pandy

[![Build](https://github.com/gccammelli/pandy/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/gccammelli/pandy/actions/workflows/build.yml)
[![Coverage](https://codecov.io/gh/gccammelli/pandy/graph/badge.svg)](https://codecov.io/gh/gccammelli/pandy)
[![Latest release](https://img.shields.io/github/v/release/gccammelli/pandy)](https://github.com/gccammelli/pandy/releases/latest)
[![License](https://img.shields.io/github/license/gccammelli/pandy)](LICENSE)
[![Open issues](https://img.shields.io/github/issues/gccammelli/pandy)](https://github.com/gccammelli/pandy/issues)

**An unofficial companion app for Bambu printers in LAN-only mode.**

Pandy was built from scratch for people who keep their Bambu printer in LAN-only mode but still want useful printer
features. It talks directly to the printer on the local network, without sending printer control through
Bambu's cloud.

These are dark-mode screens from the built-in demo printer, which lets you explore the app without a printer:

<p align="center">
  <img src="docs/images/pandy-dark-device.png" width="190" alt="Device screen with a demo print in progress">
  <img src="docs/images/pandy-dark-control.png" width="190" alt="Device control: temperatures, speed, light, fans">
  <img src="docs/images/pandy-dark-filament.png" width="190" alt="AMS and external spool filament screen">
  <img src="docs/images/pandy-dark-history.png" width="190" alt="Print history">
</p>

<p align="center">
  <img src="docs/images/pandy-dark-makerworld-print.png" width="190" alt="A MakerWorld model page inside Pandy, with the Print button">
  <img src="docs/images/pandy-dark-select-profile.png" width="190" alt="Select a print profile for a MakerWorld model">
  <img src="docs/images/pandy-dark-prepare-print.png" width="190" alt="Prepare to Print with the Print button">
  <img src="docs/images/pandy-dark-print-options.png" width="190" alt="Filament mapping and print options">
</p>

The Studio tab slices your own models on the phone:

<p align="center">
  <img src="docs/images/pandy-studio-home.png" width="190" alt="Studio: open a model or try a sample, pick the printer and the slicing engine">
  <img src="docs/images/pandy-studio-plate.png" width="190" alt="Studio: a gear on the plate, with scale and rotate tools">
  <img src="docs/images/pandy-studio-preview.png" width="190" alt="Studio: the sliced gear with print time, filament use and the Print button">
</p>

## Features

- Printer status, progress, temperatures, fans, light, speed, motion controls and HMS messages
- Pause, stop and skip controls, plus AMS and filament controls
- Print files, thumbnails, history, reprints and timelapses
- Android: alerts when a print finishes, fails or needs attention, even with the screen off (Pandy stays connected in the background; turn it off in Settings). iOS can't watch in the background, so it has no alerts
- Browse MakerWorld, Thingiverse and Printables models, then slice and print them in the app with the phone plugin on Android ([how](docs/studio.md)) or [Pandy Slicer](https://github.com/gccammelli/pandy-slicer) as a Home Assistant add-on or Docker container ([how](docs/slicer.md))
- Android: a Studio tab that opens STL and 3MF files, slices them on the phone with Bambu Studio's or OrcaSlicer's engine (an optional plugin) and prints them ([how](docs/studio.md))
- Light and dark themes, with a built-in demo printer

## Direct mode

Direct mode is the default and keeps printer traffic on your LAN. You need:

- A Bambu printer with **LAN Only Mode** and **Developer Mode** enabled
- Its **access code**
- A phone on the same network as the printer

If the printer does not appear automatically, enter its IP address and serial number. iPhone users normally need to
do this because iOS restricts the multicast announcements used for discovery.

See [the setup guide](docs/setup.md) for the printer-specific steps.

## Home Assistant is optional

Home Assistant with the [Bambu Lab integration](https://github.com/greghesp/ha-bambulab) can enhance Pandy when you
want more than a local connection. For example, it can provide:

- Access to the printer while you are away from home, through your Home Assistant remote access
- Print history, files and timelapses retained in Home Assistant's cache
- A camera bridge for printer models whose direct camera stream is not available on iOS
- Home Assistant automations and notifications based on printer state

You can add Home Assistant as an extra source while keeping direct printer control, or use it as Pandy's connection.
See [the setup guide](docs/setup.md) for both options.
Use a dedicated non-administrator Home Assistant user for Pandy's long-lived token; it can do whatever that user can. The Pandy Slicer add-on is the exception and needs an administrator user.

## Install

[![Download Android APK](https://img.shields.io/badge/Download-Android_APK-3DDC84?logo=android&logoColor=white)](https://github.com/gccammelli/pandy/releases/latest/download/pandy.apk)
[![Download iPhone IPA](https://img.shields.io/badge/Download-iPhone_IPA-000000?logo=apple&logoColor=white)](https://github.com/gccammelli/pandy/releases/latest/download/pandy.ipa)

Download the latest release for [Android or iOS](https://github.com/gccammelli/pandy/releases/latest).

- Android: install the APK.
- iPhone: install the unsigned IPA with [iLoader](https://iloader.app/), [SideStore](https://sidestore.io) or
  [AltStore](https://altstore.io), signed with your own Apple ID. Pandy is not on the App Store or Google Play.

## Build

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

Pandy is Kotlin Multiplatform with Compose: shared screens and logic live in `shared/`, Android in `app/`, and iOS in
`iosApp/`. On Linux, the iOS source can be checked with:

```bash
./gradlew :shared:compileKotlinIosArm64 -Ppandy.ios
```

Pandy is not made by or affiliated with Bambu Lab. Protocol details and Bambu's CA certificates come from the
[Home Assistant Bambu Lab integration](https://github.com/greghesp/ha-bambulab) (MIT). [Pandy Slicer](https://github.com/gccammelli/pandy-slicer)
uses [Bambu Studio](https://github.com/bambulab/BambuStudio) (AGPL-3.0), unmodified in its own container.

Developed by Giancarlo Cammelli. MIT licence.

## Contributing

Bug reports, feature requests and pull requests are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) for tests, privacy rules and the required before/after visual evidence for user-facing changes.

## Support Pandy

[![Buy me a coffee](https://img.shields.io/badge/Buy%20me%20a%20coffee-FFDD00?logo=buymeacoffee&logoColor=black)](https://buymeacoffee.com/cammelli)
