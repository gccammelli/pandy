# Pandy Slicer: printing MakerWorld models

Handy prints a MakerWorld model by having Bambu's cloud slice it. With the printer in LAN-only mode, Pandy does that
part locally: **Pandy Slicer** runs Bambu Studio's own slicer (its command line, unmodified) in a container, slices the
model's print profile and puts the result on your printer; then Pandy starts the print.

```
MakerWorld (in Pandy's Models tab) ──3MF──▶ Pandy ──3MF──▶ Pandy Slicer ──sliced file (FTPS)──▶ printer
                                              └───────────── start the print (MQTT or Home Assistant) ────▶
```

It runs on any **x86-64** machine with Docker, or as a **Home Assistant add-on** (Bambu Studio is only published for
x86-64 Linux, so not on a Raspberry Pi). It needs about 1.5 GB of disk and some CPU for a minute per print.

## Option A: Home Assistant add-on

1. Settings › Add-ons › Add-on store › ⋮ › **Repositories**, add `https://github.com/gccammelli/pandy`.
2. Install **Pandy Slicer**, start it. (The first build downloads Bambu Studio, about 220 MB; give it a few minutes.)
3. Optional: in its Configuration tab set a **token**, so only Pandy can use it.
4. In Pandy: Me › Settings › **Slicer for MakerWorld prints**: your Home Assistant's address with port **8737**
   (for example `10.1.2.10:8737`), and the token if you set one. **Save** checks it answers.

## Option B: Docker on any computer

```
git clone https://github.com/gccammelli/pandy
cd pandy/slicer
docker compose up -d
```

Then in Pandy enter `<that computer's IP>:8737`. To require a token, set `PANDY_SLICER_TOKEN` in `docker-compose.yml`
and enter the same one in Pandy.

## Printing a model

1. **Models** tab: find a model, open it, choose the **print profile for your printer** and **Download** its 3MF
   (MakerWorld asks you to sign in once). Pandy catches the download.
2. Pandy shows the plates with their pictures, the filaments and which AMS slot each will use, and Handy's options
   (bed leveling, flow calibration, timelapse). Tap **Print**.
3. The slicer slices the plate (about a minute), uploads it to the printer, and the print starts. Follow it on Devices.

In Home Assistant mode, Pandy asks once for the printer's access code: the slicer puts the file on the printer over the
local network, which needs it.

## Good to know

- **Use the profile for your printer.** The slicer slices with the profile's own settings; a profile made for another
  model is flagged, and may not print right.
- **Filament**: each filament goes to the AMS slot with the same type and the nearest colour, else the external spool.
- **Plate pictures**: Bambu Studio's command line can't draw them without a screen, so the slicer keeps the ones the
  profile came with (MakerWorld profiles have them).
- **Safety**: the slicer only accepts 3MF files, runs one slice at a time, and only talks to the printer you name. Don't
  expose port 8737 to the internet; use the token if others share your network.

## API (for the curious)

| | |
|---|---|
| `GET /health` | `{"ok": true, "version": "<Bambu Studio version>"}` |
| `POST /slice?plate=1&name=<name>` | body: the 3MF; answers the sliced `.gcode.3mf`, with a JSON summary in `X-Pandy-Result` |
| `POST /slice?plate=1&name=<name>&upload=1` | also uploads it: headers `X-Printer-Ip`, `X-Printer-Serial`, `X-Printer-Code`; answers `{"path": "cache/<name>.gcode.3mf", "plates": […]}` |

With a token set, send `Authorization: Bearer <token>`.
