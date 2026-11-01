# Printing models from MakerWorld, Thingiverse and Printables

Handy prints a MakerWorld model by having Bambu's cloud slice it. With the printer in LAN-only mode Pandy does that
part locally, with **[Pandy Slicer](https://github.com/gccammelli/pandy-slicer)**: Bambu Studio's own slicer in a
container, on your Home Assistant or any x86-64 computer.

```
MakerWorld, Thingiverse or Printables (Pandy's Models tab) ──3MF / STL──▶ Pandy ──3MF──▶ Pandy Slicer ──sliced file (FTPS)──▶ printer
                                           └──────────── start the print (MQTT or Home Assistant) ─────▶
```

Everything else in Pandy (status, camera, controls, history, reprints, timelapses) works without it.

## Set it up

**With Home Assistant** (Pandy reaches the slicer through it, so it works wherever Pandy reaches Home Assistant):

1. Install the **Pandy Slicer add-on** (one click from
   [its page](https://github.com/gccammelli/pandy-slicer#install-home-assistant-add-on)) and start it.
2. That's it: Pandy finds the add-on through its Home Assistant connection. **Me › Settings › Slicer for model
   prints › Check the add-on** confirms it answers. Pandy's Home Assistant user must be an administrator (Home
   Assistant only lets administrators reach add-ons).

```
Phone ──▶ Home Assistant ──▶ Pandy Slicer add-on ──sliced file (FTPS)──▶ printer
```

**Without Home Assistant**: run it with **Docker**
([instructions](https://github.com/gccammelli/pandy-slicer#install-docker-anywhere)), then in **Me › Settings › Slicer
for model prints** enter its address with port **8737** (for example `slicer.local:8737`) and the token if you set
one. **Save** checks it answers. An address entered here is used instead of the add-on.

## Print a model

1. **Models** tab (tap it again, or press and hold it, to switch between MakerWorld, Thingiverse and Printables): open a model and tap the magenta **Print** (where MakerWorld shows "Open in Bambu Handy"; on models
   without it, Pandy's Print sits above "Download 3MF", which stays a plain download). Sign in to MakerWorld once.
2. Pandy picks the profile made for your printer when the model has one, and shows its plates with their pictures,
   the filaments and which AMS slot each uses, and Handy's options (bed leveling, flow calibration, timelapse).
3. **Print**: the slicer slices the plate (about a minute), converting a profile made for another printer to yours,
   puts it on the printer, and the print starts with Bambu Studio's usual preparation.

## When Print doesn't show

The printer must take commands from Pandy: **LAN Only mode** and **Developer Mode** on (printer: Settings › Network,
or WLAN on P1/A1). Otherwise Pandy shows "Watching only", leaves MakerWorld as it is, and its "Open in Bambu Handy"
opens Handy.
