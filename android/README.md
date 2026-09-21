# The app

Kotlin + Jetpack Compose, one activity, `com.celicadash.app`. The car is drawn in OpenGL ES 2 from
the packed wireframes under `app/src/main/assets/model/`; the Garage and the Manual read the car
pack that `pack/celica/` provides at build time (`app/build.gradle.kts` syncs it into the APK as
`assets/site/`).

## Build

Needs JDK 17 and an Android SDK with platform 34. Put `sdk.dir=<your sdk>` in `local.properties`.

```
cd android
gradlew assembleDebug            # app/build/outputs/apk/debug/app-debug.apk
gradlew testDebugUnitTest        # ELM327 and navigation tests
```

A release build is signed with the key named in `keystore.properties` (see the comment at the top
of `app/build.gradle.kts`); without that file it carries the debug key. `python make_release.py`
in the repo root builds everything and zips the distributable.

## Layout

```
app/src/main/kotlin/com/celica/dash/
  MainActivity.kt        the one activity; reads the owner's choices before the first frame
  apps/                  UnitProfile (what kind of unit), PhoneLink (CarPlay / Android Auto), RootShell, Apps
  data/                  Prefs, CarSpec, Units, Region, Helper (the PC helper), SiteData (the car pack), stores
  ui/setup/              the first-run Setup wizard and Permissions
  ui/Shell.kt            strip + dock + sections, boot ceremony, settings sheet
  ui/DashViewModel.kt    the one state holder
  ui/home, ui/drive, ui/map, ui/media, ui/phone, ui/garage, ui/settings, ui/theme
  car/                   CarModel (loader), CarRenderer (GLES2), CarView (Compose)
  obd/                   ELM327, PIDs, Bluetooth transport, the demo drive
  media/, phone/, location/, weather/, routing/, search/, lights/
```

## Emulator

`tools/emu.py` drives an AVD shaped like a head unit (1024×600, density 120, landscape) with
`ANDROID_SDK_ROOT` set. The emulator has no useful Bluetooth, so the dongle and the phone are
dead there; the Setup wizard, the map, the routing and the music all run. The PC helper is
`10.0.2.2` from inside it.
