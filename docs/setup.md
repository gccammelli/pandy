# Setting up Pandy

## Direct mode (the default)

Pandy connects to the printer itself, on your local network.

### On the printer

| Printer | Where |
|---|---|
| X1, X1E, H2D, H2S, P2S | Settings › Network: **LAN Only Mode** on, **Developer Mode** on, note the **Access Code** |
| P1P, P1S, A1, A1 mini | Settings › WLAN: **LAN Only Mode** on, **Developer Mode** on, note the **Access Code** |

Developer Mode lets apps other than Bambu's control the printer (Bambu added this switch in 2025 firmware). The access
code changes when you switch LAN mode off and on again; enter the new one in Pandy (Me › Settings › Disconnect, then
connect again).

### In Pandy

The first screen lists the printers on your Wi-Fi. Pick yours, type the access code, tap **Connect**.

Not listed? Some routers block the multicast the printers announce themselves with (or the phone is on a guest
network). Type the printer's **IP address** (Settings › Network or WLAN on the printer) and **serial number**
(Settings › Device) instead. A fixed IP address for the printer (a DHCP reservation in your router) saves typing it again
when it changes.

### What direct mode can't do

- Work away from home: the printer is only reachable on your network. Use Home Assistant for that (below).
- Show prints the printer has deleted from its storage. Home Assistant's file cache keeps them.

## Home Assistant (optional)

Pandy uses Home Assistant's **Bambu Lab** integration ([ha-bambulab](https://github.com/greghesp/ha-bambulab), install
it from HACS), which you add your printer to there.

**Add it to direct mode**: Me › Settings › Home Assistant. Pandy keeps controlling the printer directly and takes print
history, files and timelapses from Home Assistant.

**Or use it as the connection**: on Pandy's first screen, *Use Home Assistant instead*. Everything goes through Home
Assistant, so Pandy works wherever your Home Assistant is reachable (for example through Nabu Casa or your own
remote access).

You need:

- Home Assistant's address, as you'd open it in a browser (`http://homeassistant.local:8123`, or your remote address).
- A **long-lived access token**: in Home Assistant, your profile › Security › Long-lived access tokens › Create token.
  Use a dedicated non-administrator Home Assistant user for the app's token; it can do whatever that user can. The Pandy Slicer add-on is the exception and needs an administrator user.

### File cache

Pandy's History and Files come from the integration's **file cache**: Settings › Devices & services › Bambu Lab ›
Configure: keep the print cache on, and set how many timelapses to keep.

## Troubleshooting

| You see | Try |
|---|---|
| *The printer refused the access code* | The code changes when LAN mode is toggled: read it again on the printer. |
| *Couldn't reach the printer* | Is the phone on the same network (not a guest Wi-Fi)? Is the IP address current? |
| Connects, but controls do nothing | Turn on **Developer Mode** on the printer. |
| *This printer's certificate is for …* | The IP address now belongs to a different printer: check it. |
| No camera picture | X1/H2: Settings › General › **LAN Mode Liveview** on the printer. |
