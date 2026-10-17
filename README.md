# Pandy

**Bambu Handy, for printers in LAN-only mode.** Pandy looks and works like Bambu Lab's Handy app, but talks to your
printer on your own network: no Bambu cloud between your phone and your printer, so it keeps working with LAN-only
mode switched on.

- **Devices**: live camera, the print with its picture, progress and time left, Skip / Pause / Stop; temperatures,
  fans, light, speed, the XYZ jog pad, extruder, AMS slots with humidity, load and unload; printer messages (HMS).
- **Skip Parts**: tap a part on the plate picture.
- **History**: every print file on the printer, with its picture; print one again with your AMS mapped automatically.
- **Timelapses**: watch them from the printer's storage.
- **Models**: MakerWorld, signed in with your own account. Download a model's print profile and Pandy slices it
  (with Bambu Studio, see below) and starts it on your printer.
- Light and dark themes. A **demo** with a pretend printer, to try everything first.

Pandy is not made by or affiliated with Bambu Lab.

## What you need

| | Needed for |
|---|---|
| A Bambu Lab printer with **LAN Only mode** and **Developer Mode** on, its **access code** | everything |
| Your phone on the same network as the printer | direct mode (the default) |
| *Optional:* [Home Assistant](https://www.home-assistant.io/) with the [Bambu Lab integration](https://github.com/greghesp/ha-bambulab) | using Pandy away from home; prints the printer has since deleted |
| *Optional:* [Pandy Slicer](docs/slicer.md) (a Home Assistant add-on, or Docker on any x86-64 computer) | printing MakerWorld models |

Tested on an X1 Carbon. P1, A1, H2 and P2 printers speak the same protocol; their camera works differently (P1 and A1
send pictures rather than video) and is supported, but less tested.

## Install

Download `pandy.apk` from the [latest release](https://github.com/gccammelli/pandy/releases/latest) and open it on your
Android phone (allow installing from your browser when asked).

## Set up

1. **On the printer**: Settings › Network (X1, H2) or Settings › WLAN (P1, A1): turn on **LAN Only Mode** and
   **Developer Mode**, and note the **Access Code**.
2. **In Pandy**: your printer appears under *Printers on this Wi-Fi* (printers announce themselves every few seconds).
   Pick it, type the access code, **Connect**. If it doesn't appear, type its IP address and serial number (both on the
   printer's screen).

That's it. More in [docs/setup.md](docs/setup.md), including adding Home Assistant.

## How it works

Pandy speaks the printer's own local protocols, the ones Bambu Studio uses in LAN mode:

- **MQTT over TLS** (port 8883) for the printer's state and every control,
- **FTPS** (port 990) for print files, thumbnails and timelapses,
- the **camera**: RTSP over TLS (port 322) on X1 and H2, a picture stream (port 6000) on P1 and A1.

The printer's certificate is checked against Bambu's device certificate authorities and must carry the printer's
serial number, so Pandy knows it's talking to your printer. Your access code stays on the phone (encrypted with the
Android Keystore).

Home Assistant is optional: added in Me › Settings, it supplies print history and files from its own cache; used as the
connection (choose it on the first screen) it lets Pandy work away from home.

## Building

```
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest   # tests
```

Kotlin Multiplatform with Compose; the app is Android, the code is shared (`shared/`).

## Credits

- Protocol details and Bambu's CA certificates from the [Home Assistant Bambu Lab integration](https://github.com/greghesp/ha-bambulab) (MIT).
- Slicing by [Bambu Studio](https://github.com/bambulab/BambuStudio) (AGPL-3.0), run unmodified in its own container.

Developed by Giancarlo Cammelli. MIT licence.
