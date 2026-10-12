# BYD vendor permissions on DiLink 1.0 (Android 7.1)

Firmware audit of DiLink 1.0 (BYD Song Pro 2019, platform MSM8953 / Android 7.1.2, firmware package `VP128_1.38.2103120.14.2103090.zip`) found a BYD-specific permission scheme that lets a third-party app request a handful of vehicle-information permissions and have them granted by the driver through BYD's own Settings UI, without a platform signature. Writes still need a signature; the user-grantable permissions only open read-side access to the BYD SDK proxy.

This document records what was observed in the firmware. Which SDK calls then actually return data once the permission is granted, and how useful each piece is to DiPlay, is not yet confirmed on hardware. The companion issue tracks that verification.

## What the HUD test package already requests

Only the `debug` buildType of `:mobile` adds the extra permission; the public release never requests it. See [mobile/src/debug/AndroidManifest.xml](mobile/src/debug/AndroidManifest.xml) line 2:

```xml
<uses-permission android:name="android.permission.BYDAUTO_INSTRUMENT_COMMON" />
```

The HUD test APK is installed on the car as a separate package (`com.shihab.diplay.hudtest`) and shows "Auto Instrument Info / 仪表信息" in BYD Settings → Apps → DiPlay HUD Test → Permissions. The driver turns it on manually.

Why this works, in short: `BYDAutoInstrumentDevice.getInstance(Context)` performs a caller-side `checkCallingOrSelfPermission("BYDAUTO_INSTRUMENT_COMMON")` inside the app's process. Without the permission that call throws SecurityException, so the release build reaches the device through its private constructor by reflection instead ([shared/.../BydClusterSong.kt:335](../shared/src/main/java/com/shilapi/xcertplay/hud/BydClusterSong.kt#L335)). Writes that the SDK forwards to `autoservice` are additionally gated by `BYDAUTO_INSTRUMENT_SET` (signature) at the service side; DiPlay bypasses that by invoking its tool as the ADB shell user through `app_process`.

## Permission scheme in `framework-res.apk`

All `BYDAUTO_*` permissions are declared inside the stock `framework-res.apk` (not a separate vendor APK). They use a three-tier scheme:

| Suffix              | protectionLevel      | Grantable to a third-party APK? |
|---------------------|----------------------|---------------------------------|
| `*_SET` (write)     | `signature` (0x02)   | No — needs the platform cert    |
| `*_GET` (read)      | `signature` (0x02)   | No                              |
| `*_COMMON` (common) | `dangerous` (0x01)   | Yes, at runtime                 |

A few of the `*_GET` permissions on this firmware carry the extra privileged flag (`signature|privileged`, 0x12), but all `*_COMMON` permissions are plain `dangerous`.

The 20 `*_COMMON` permissions declared on this firmware:

```
BYDAUTO_AC_COMMON          BYDAUTO_LIGHT_COMMON
BYDAUTO_AUDIO_COMMON       BYDAUTO_MULTIMEDIA_COMMON
BYDAUTO_BODYWORK_COMMON    BYDAUTO_PANORAMA_COMMON
BYDAUTO_CHARGING_COMMON    BYDAUTO_PM2P5_COMMON
BYDAUTO_DOOR_LOCK_COMMON   BYDAUTO_RADAR_COMMON
BYDAUTO_ENERGY_COMMON      BYDAUTO_SAFETY_BELT_COMMON
BYDAUTO_ENGINE_COMMON      BYDAUTO_SETTING_COMMON
BYDAUTO_GEARBOX_COMMON     BYDAUTO_SPEED_COMMON
BYDAUTO_INSTRUMENT_COMMON  BYDAUTO_STATISTIC_COMMON
BYDAUTO_TIME_COMMON        BYDAUTO_TYRE_COMMON
```

None of them declare a `permissionGroup`. Stock AOSP's runtime permission dialog only surfaces dangerous permissions that belong to a group, so on a stock Android 7 build these permissions could not reach a `requestPermissions` dialog.

## BYD's custom grant path

DiLink 1.0 ships a modified `PackageInstaller` (`/system/priv-app/PackageInstaller/oat/arm64/PackageInstaller.odex`) with a BYD-specific whitelist and a "granted by default" branch. The following symbols are present in the OAT:

```
BYD_PERMISSIONS                               // String[] constant
isBYDPermissions / bydPermission              // helper predicate
hasGrantedByDefaultPermission(s)              // new branch on AppPermissionGroup
isGrantedByDefault / grantedByDefault
Landroid/acquisitionservice/BYDAcquisitionClient;   // consent/audit upload
```

The literal string pool emits the `BYD_PERMISSIONS` whitelist in order:

```
android.permission.BYDAUTO_AC_COMMON
android.permission.BYDAUTO_BODYWORK_COMMON
android.permission.BYDAUTO_DOOR_LOCK_COMMON
android.permission.BYDAUTO_INSTRUMENT_COMMON
android.permission.BYDAUTO_PANORAMA_COMMON
android.permission.BYDAUTO_SETTING_COMMON
```

On the car these six show up as toggles in BYD's Settings → App → *Permissions*. The driver flips them manually; there is no runtime prompt from `requestPermissions()`. Confirmed on BYD Song Pro 2019 (DiLink 1.0): `BYDAUTO_INSTRUMENT_COMMON` and `BYDAUTO_AC_COMMON` have both been toggled on this way.

The other 14 `*_COMMON` permissions are still `dangerous` and should be grantable through `adb shell pm grant <pkg> android.permission.BYDAUTO_<name>_COMMON`, but they do not appear in BYD's Settings whitelist, so an ordinary driver cannot authorise them.

## User-grantable permissions, with labels from the firmware

Only these six surface in BYD Settings on DiLink 1.0. Chinese labels/descriptions come from `framework-res.apk` resource strings.

| Permission                        | 中文标题        | 中文说明                                     | English label               |
|-----------------------------------|-----------------|----------------------------------------------|-----------------------------|
| `BYDAUTO_AC_COMMON`               | 空调信息        | 获得空调信息，包括空调温度等                 | Auto Ac Info                |
| `BYDAUTO_BODYWORK_COMMON`         | 汽车车身信息    | 获得车身信息，包括车架号等                   | Auto Bodywork Info          |
| `BYDAUTO_DOOR_LOCK_COMMON`        | 门锁信息        | 获得门锁信息，包括门锁状态等                 | Auto Doorlock Info          |
| `BYDAUTO_INSTRUMENT_COMMON`       | 仪表信息        | 获得仪表信息，包括背光亮度等                 | Auto Instrument Info        |
| `BYDAUTO_PANORAMA_COMMON`         | 全景、摄像头信息 | 获得全景、摄像头信息，包括旋转屏状态等       | Auto Panorama Info          |
| `BYDAUTO_SETTING_COMMON`          | 车辆设置信息    | 获得车辆设置信息，包括转向助力模式设置等     | Auto SETTING Info           |

## What this does not change

- Writes to the instrument cluster, air conditioning, body etc. still require the signature `*_SET` permission at the `autoservice` boundary. DiPlay already handles instrument-cluster writes by invoking its own tool through ADB (`app_process` as the shell user), which bypasses the service-side check. Granting a `*_COMMON` permission does not open any new write path.
- Reads through the SDK's `*_GET` methods are gated by `BYDAUTO_*_GET` (signature) at the service side. The `*_COMMON` permission only opens the SDK caller-side gate (`getInstance`, callback registration). Whether a given read value is reachable after that gate opens — and what it looks like — is exactly what still needs to be verified on the car.

## Open verification questions (per permission)

Each of the six user-grantable permissions should be measured on a real DiLink 1.0 head unit for:

1. Can the HUD test APK construct the matching `BYDAutoXxxDevice` through `getInstance(context)` (no reflection fallback) after the driver flips the toggle? Baseline is that `getInstance` throws SecurityException without the permission.
2. Which read-only API calls on that device actually return data vs. return a service-side permission failure?
3. For each successful read, is the value meaningful to DiPlay (e.g. are there feature IDs under `BYDAutoFeatureIds$<Nested>` that we would use)?

The HUD test build already ships `BydVendorApiProbe` ([shared/.../BydVendorApiProbe.kt](../shared/src/main/java/com/shilapi/xcertplay/hud/BydVendorApiProbe.kt)) and a `Byd13CatalogProbeMain` ([shared/.../Byd13CatalogProbeMain.kt](../shared/src/main/java/com/shilapi/xcertplay/hud/Byd13CatalogProbeMain.kt)) which can enumerate the vendor SDK's feature-id catalogue in a diagnostic report. These are the natural starting point for the per-permission probes.

## Comparison to Android 10 references

The public write-up at `wheregoes/byd-dolphin-hacking` describes the same `BYDAUTO_*` family on DiLink 4 / Android 10. The *names* carry across generations, but the car-side grant plumbing does not: on Android 10 BYD's permission UI lives in `PermissionController`, not `PackageInstaller`, and the whitelist and defaults are different. The six-permission whitelist above is specific to DiLink 1.0 and should not be extrapolated to newer DiLink versions without re-checking the installed `PackageInstaller`/`PermissionController`.
