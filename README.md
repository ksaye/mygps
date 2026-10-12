# My GPS

An ad-free GPS app for Android: precise position readout, a map that works with no signal,
a compass, and an Android Auto screen. No account, no tracking, no ads; the only network
traffic is map tiles, optional offline-map downloads, and the update check against this
repository's releases.

## Features

**Data tab**
- Latitude / longitude in degrees-minutes-seconds (tap for decimal degrees)
- Compass heading, corrected to true north from the local magnetic declination
- Satellites used in the fix / visible (all constellations)
- Horizontal accuracy, time to first fix, time of the last fix
- Speed (mph) and altitude above mean sea level (from the receiver's NMEA GGA sentence,
  falling back to Android's own MSL altitude, then the WGS84 ellipsoid height, labelled as such)
- [Plus code](https://maps.google.com/pluscodes/) (11-character Open Location Code)
- Copy coordinates, share coordinates + plus code + map link

**Map tab**
- OpenStreetMap with your position as an arrow (direction of travel/facing), an accuracy
  circle, and the trail since the app opened; re-center button
- **Offline maps.** A world map (coastlines, borders, major cities) is built in. More detail:
  tap the download button and pick a country or US state from
  [download.mapsforge.org](https://download.mapsforge.org/) (e.g. Panama 37 MB, Texas 569 MB).
  Downloads are Wi-Fi only unless you allow mobile data, run in the background and resume.
- With working internet the map is the online one; downloaded maps fill in any tile that
  fails to load. With no internet (including an unsigned-in captive portal, e.g. ship Wi-Fi)
  the map draws entirely from the offline maps. The map opens at your last known position.

**Compass tab** — full-screen rotating dial, 16-point direction, tap to switch true/magnetic
north (shows the declination), and a warning when the magnetometer needs a figure-8 calibration.

**Android Auto** — a navigation-category car app: the same map with speed, course, altitude,
coordinates and satellites, zoom/pan/center, and a compass view. In the car, direction comes
from the GPS course rather than the phone's magnetometer, which is useless in a car mount.
Sideloaded apps only appear in Android Auto with *Unknown sources* enabled in Android Auto's
developer settings.

**Updates** — the app checks this repository's latest GitHub release (at launch, at most every
6 hours, or by tapping the version line) and offers it through Android's installer prompt.

## Install

Download the APK from [Releases](https://github.com/ksaye/mygps/releases) and open it, or
`adb install -r MyGPS-<version>.apk`. Android 8.0+.

## Build

JDK 17 and the Android SDK (platform 36).

```sh
./build-release.sh          # unsigned: dist/MyGPS-<version>.apk
MYGPS_KEYSTORE=release.keystore MYGPS_KEYSTORE_PASSWORD=... ./build-release.sh   # signed
```

Native Kotlin, Android Views; maps by [osmdroid](https://github.com/osmdroid/osmdroid) and
[Mapsforge](https://github.com/mapsforge/mapsforge); Android Auto via the AndroidX Car App Library.

## Attribution

Map data © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright).
Offline maps from [download.mapsforge.org](https://download.mapsforge.org/).

## License

MIT — see [LICENSE](LICENSE).
