# FleetServer telemetry API

The app runs a small HTTP server on the phone (default port **8080**) and accepts reports from
any device on the same network — including devices joined to the phone's own hotspot.

Everything below works from an ESP32, a laptop, or `curl`.

## Endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/telemetry` | Submit a report (JSON body) |
| `GET` | `/telemetry?lat=..&lon=..&state=..` | Submit a report (query string) |
| any | raw TCP | Send one line — `esp32-01,6.52,3.37,4` or a JSON object |
| `GET` | `/` (or `/map`) | **Live web map** — open it from any laptop on the same network |
| `GET` | `/api/devices` | JSON snapshot of the fleet (`?trail=1` also returns the trails) |
| `GET` | `/api/devices/<id>` | JSON for one device including its trail |
| `GET` | `/api/log` | JSON traffic log |
| `GET` | `/health` | `ok` — quick reachability check |

Responses are JSON. `Access-Control-Allow-Origin: *` is set, so a browser page can post too.

## Web dashboard

Open `http://<phone-ip>:8080/` in any browser on the same network (or on the phone's hotspot) and
you get the same view the app shows:

* a map with every tracker — **OpenStreetMap tiles** when the browser has internet, and a
  **built-in latitude/longitude grid** when it does not (both are drawn from `/api/devices?trail=1`)
* pins coloured by state, trails, drag to pan, wheel/double-click to zoom
* a live device list with coordinates, packet counts, satellites and "last seen"
* Fit all / Follow / map-source / refresh-interval controls
* auto-refreshes every 2 s by default

Nothing external is required: if Leaflet cannot be fetched, the page silently stays on the grid map,
so it still works when the phone is a hotspot with no internet.

## Accepted payload shapes

The parser is deliberately forgiving, so a shell, a serial monitor or a half-finished sketch can
all feed it. Every one of these is the same report:

```bash
{"device":"esp32-01","lat":6.5244,"lon":3.3792,"state":4}   # JSON
{device:esp32-01,lat:6.5244,lon:3.3792,state:4}             # JSON, quotes missing
device:esp32-01,lat:6.5244,lon:3.3792,state:4               # key:value
lat=6.5244&lon=3.3792&state=4                               # query string
esp32-01,6.5244,3.3792,4                                    # ordered values
esp32-03,,,1                                                # no fix - leave the coords blank
esp32-03 1                                                  # just a device id and a state
```

Surrounding quotes are stripped, so a body that `cmd.exe` passed through with its single quotes
still works. A device with no fix can leave `lat` and `lon` empty (`esp32-03,,,1`), send `0` for
both, or omit them altogether — the report is kept, the pin is not drawn, and the device stays in
the list with its state. Only one of the two coordinates arriving is treated the same as neither.

> **Windows cmd.exe** does not treat single quotes as quotes, so `'{"lat":6.52}'` reaches the phone
> as `lat:6.52` fragments, and the `\"` escapes that come back inside a JSON error message are not
> something you can paste into a terminal. Skip escaping altogether — these work as typed:
>
> ```bat
> curl -X POST http://<phone-ip>:8080/telemetry -d esp32-01,6.5244,3.3792,4
> curl -X POST http://<phone-ip>:8080/telemetry -d esp32-03,,,1
> curl -X POST http://<phone-ip>:8080/telemetry -d "device=esp32-01&lat=6.5244&lon=3.3792&state=4"
> ```
>
> The `&` needs the double quotes; the comma forms do not. In bash, zsh, PowerShell and WSL the
> JSON form works with single quotes:
> `curl -X POST http://<phone-ip>:8080/telemetry -d '{"device":"esp32-01","lat":6.5244,"lon":3.3792,"state":4}'`.

## Report fields

| Field | Aliases | Required | Notes |
| --- | --- | --- | --- |
| `device` | `deviceId`, `device_id`, `id`, `name`, `vehicle`, `unit` | no* | Defaults to `esp32-<source-ip>` |
| `lat` | `latitude` | no** | Degrees, `-90 … 90` |
| `lon` | `lng`, `long`, `longitude` | no** | Degrees, `-180 … 180` |
| `state` | `status`, `mode`, `gps_state` | no** | `1`–`4` or a name (below) |
| `speed` | `spd`, `velocity` (m/s), `speed_kmh`, `kmh` | no | `speed` is m/s |
| `heading` | `course`, `cog`, `bearing` | no | Degrees from north |
| `altitude` | `alt`, `elevation` | no | Metres |
| `accuracy` | `acc` | no | Metres |
| `hdop` | | no | |
| `satellites` | `sats`, `sat` | no | |
| `battery` | `batt`, `vbat` | no | Percent or volts, as sent |
| `rssi` | `signal` | no | dBm |
| `uptime` | `millis`, `uptime_ms` | no | Device clock, stored as sent |
| `note` | `message`, `msg` | no | Free text shown in the detail sheet |

\* A report is rejected only when it carries neither a device id, nor a position, nor a state.
\** Sending `lat=0&lon=0`, or leaving `lat`/`lon` blank, is treated as **no position** (trackers
do this before the first fix).
If a valid position arrives without a `state`, the app assumes `4` (operational).

## Device states

| Code | Name | Meaning | Colour in the app |
| --- | --- | --- | --- |
| `1` | `GPS_NOT_FOUND` | GPS device not found | red |
| `2` | `NO_FIX` | GPS present but no fix yet | amber |
| `3` | `OFFLINE` | Offline mode — buffered / cached fix | blue |
| `4` | `OPERATIONAL` | Live fix | green |

Names are matched loosely: `no_gps_device`, `not_found`, `no_fix`, `searching`, `offline`,
`cached`, `operational`, `online`, `ok`, `fix`, `locked`, `tracking` all work. Anything else
becomes `UNKNOWN` (grey) and the packet is still recorded.

## Examples

```bash
# JSON
curl -X POST http://192.168.43.1:8080/telemetry \
  -d '{"device":"esp32-01","lat":7.3775,"lon":3.9470,"state":4,"sats":9,"speed":12.3}'

# query string (simplest for microcontrollers)
curl "http://192.168.43.1:8080/telemetry?device=esp32-01&lat=7.3775&lon=3.9470&state=4"

# raw TCP line
printf 'esp32-01,7.3775,3.9470,4\n' | nc 192.168.43.1 8080

# read the fleet back
curl http://192.168.43.1:8080/api/devices
```

On the ESP32 (Arduino core):

```cpp
HTTPClient http;
http.begin("http://192.168.43.1:8080/telemetry");
http.addHeader("Content-Type", "application/json");
http.POST("{\"device\":\"esp32-01\",\"lat\":7.3775,\"lon\":3.9470,\"state\":4}");
http.end();
```

A complete sketch is in [`esp32/FleetClient/FleetClient.ino`](../esp32/FleetClient/FleetClient.ino).

## Response codes

| Code | When |
| --- | --- |
| `200` | Accepted (also for raw TCP clients, answered with plain text `OK`) |
| `400` | Payload could not be parsed, or latitude/longitude out of range |
| `404` | Unknown path (the request is still shown in the traffic log) |
