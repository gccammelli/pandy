# Studio: slicing on the phone

Pandy's **Studio** tab is a small plate editor: open STL and 3MF files, arrange them on the plate, pick a printer,
filament and process preset, slice, look at the layers and send the print to the selected printer. Slicing runs on the
phone itself, with Bambu Studio's own slicing engine, so it needs no computer, container or cloud. Android only for
now; the tab is hidden on iOS.

```
Pandy (MIT)                                         Slicer plugin (AGPL, separate app)
  Studio tab: plate, presets, preview    ──job──▶     Bambu Studio's engine, unchanged
  print: FTPS + MQTT to the printer      ◀─3MF───     io.github.gccammelli.pandy.slicer
```

## The slicer plugin

The engine is Bambu Studio's command-line slicer built for Android. It is AGPL, so it is not part of Pandy: it is a
separate app without a launcher icon, about 90 MB, that Pandy installs on request.

1. Open **Studio**. Without the plugin the tab shows **Get the slicer**.
2. Tap it. Pandy downloads the plugin from the latest
   [Pandy Studio release](https://github.com/gccammelli/pandy-studio/releases), checks it and hands it to Android's
   installer. Android asks once whether Pandy may install apps, then asks to confirm the install.
3. Back in Pandy the tab becomes the editor.

Before anything is installed Pandy checks that the file is the size and has the SHA-256 the release publishes, that it
is the slicer package and that it is signed with the same key as this Pandy. A file that fails any check is deleted
and not offered for installing.

Pandy only ever talks to a plugin signed with its own key, and the plugin only answers apps signed with that key
(an Android `signature` permission). A Pandy built from source therefore needs a plugin built from source with the
same key; the plugin from the releases works with Pandy from the releases.

To remove it, uninstall **Pandy Slicer** in Android's app settings. Pandy keeps working; Studio shows **Get the
slicer** again.

## Using it

* **Open models**: **Open a model** in the tab (**Add** on the plate), or **Open with › Pandy Studio** (or Share) on an STL or 3MF file in
  any other app. Files up to 100 MB (400 MB unpacked).
* **Presets**: Bambu Studio's printer, filament and process presets, read from the plugin. A new plate starts on the
  printer selected in Pandy.
* **Slice**, then **Preview** shows the layers, time and filament.
* **Print** opens Pandy's print sheet: the printer, which loaded spool each filament uses, and the usual options.
  Nothing is sent until you confirm there. The printer must be online, idle and reachable on the LAN
  ([LAN Only and Developer Mode](setup.md)), as for every other print Pandy starts.

## MakerWorld, Thingiverse and Printables

Models from the **Models** tab are sliced by [Pandy Slicer](slicer.md) on your Home Assistant or a computer by
default. With the plugin installed, **Me › Settings › Slicer for model prints › Slicer plugin on this phone** slices
them on the phone instead, with the same engine and the same arguments the server uses.

## Is it the same as Bambu Studio?

The engine is Bambu Studio's source, unmodified, at the version the Studio tab shows under **Slicing engine**. The phone's output is
compared with the official x86-64 build over 440 model and setting combinations: most are byte-identical, the rest
differ by hundredths of a millimetre on a few lines, and none differ significantly. The method, the full table and the
known differences are in the
[equivalence report](https://github.com/gccammelli/pandy-studio/blob/main/docs/equivalence/README.md).

Slicing is slower than on a computer: seconds for a small part, minutes for a full plate on an older phone.

## Limits

* One plate, whole objects: no painting, modifiers, cutting or per-object settings.
* The editor takes plain models. A 3MF project (several plates, its own print settings) opened with Pandy goes to
  the print sheet the Models tab uses instead, which slices the original project with [Pandy Slicer](slicer.md) or
  the plugin.
* Phones with a 64-bit ARM processor, Android 8 or newer.
