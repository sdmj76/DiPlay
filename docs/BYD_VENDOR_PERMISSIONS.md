# BYD vendor permissions on DiLink 1.0 (Android 7.1)

Firmware audit of DiLink 1.0 (BYD Song Pro 2019, platform MSM8953 / Android 7.1.2, firmware package `VP128_1.38.2103120.14.2103090.zip`) found a BYD-specific permission scheme that lets a third-party app request a handful of vehicle-information permissions and have them granted by the driver through BYD's own Settings UI, without a platform signature. Writes still need a signature. On this firmware, so do reads — see the measured result below.

This document records what was observed in the firmware, and — since 2026-10-14 — what the car actually returns.

**Measured result: the grants do not open reads.** With all six original permissions granted, the HUD test app (uid 10009) got `SecurityException: Neither user 10009 nor current process has android.permission.BYDAUTO_<X>_GET` from every data getter. 204 of the 222 zero-argument readers across the nine device classes threw; the 18 that returned a value are the nine `getType()` calls (the device id) and the nine `getFeatureList()` calls (all `null`). The firmware says why: `getInstance` is the only method that asks for `*_COMMON`, `registerListener` asks for `*_GET`, and every accessor asks for `*_GET` or `*_SET`. Both of those are `signature`, so no toggle a driver can flip will satisfy them. Details in "Measured on the car" below.

Nine permissions are in scope. Six are known good on the car (the driver has flipped them). Three more — charging, gearbox and audio — were added to the HUD test build on 2026-10-13 after the audit below; their `getInstance` is *not* gated at all, and their readers are refused on the same `*_GET` as everything else.

## What the HUD test package already requests

Only the `debug` buildType of `:mobile` adds the extra permissions; the public release never requests them. See [mobile/src/debug/AndroidManifest.xml](mobile/src/debug/AndroidManifest.xml).

The original six, all `BYDAUTO_*_COMMON`, shipped first:

```xml
<uses-permission android:name="android.permission.BYDAUTO_AC_COMMON" />
<uses-permission android:name="android.permission.BYDAUTO_BODYWORK_COMMON" />
<uses-permission android:name="android.permission.BYDAUTO_DOOR_LOCK_COMMON" />
<uses-permission android:name="android.permission.BYDAUTO_INSTRUMENT_COMMON" />
<uses-permission android:name="android.permission.BYDAUTO_PANORAMA_COMMON" />
<uses-permission android:name="android.permission.BYDAUTO_SETTING_COMMON" />
```

The HUD test APK is installed on the car as a separate package (`com.shihab.diplay.hudtest`) and shows "Auto Instrument Info / 仪表信息" in BYD Settings → Apps → DiPlay HUD Test → Permissions. The driver turns it on manually.

Three more were added afterwards, plus one stock Android permission kept only as a control:

```xml
<uses-permission android:name="android.permission.BYDAUTO_CHARGING_COMMON" />
<uses-permission android:name="android.permission.BYDAUTO_GEARBOX_COMMON" />
<uses-permission android:name="android.permission.BYDAUTO_AUDIO_COMMON" />
<uses-permission android:name="android.permission.BODY_SENSORS" />
```

The `:mobile` build has `targetSdk = 37`, so none of these are granted at install time. They need either the driver's toggle or `adb shell pm grant`.

Why this works, in short: `BYDAutoInstrumentDevice.getInstance(Context)` performs a caller-side `checkCallingOrSelfPermission("BYDAUTO_INSTRUMENT_COMMON")` inside the app's process. Without the permission that call throws SecurityException, so the release build reaches the device through its private constructor by reflection instead ([shared/.../BydClusterSong.kt:335](../shared/src/main/java/com/shilapi/xcertplay/hud/BydClusterSong.kt#L335)). Writes that the SDK forwards to `autoservice` are additionally gated by `BYDAUTO_INSTRUMENT_SET` (signature) at the service side; DiPlay bypasses that by invoking its tool as the ADB shell user through `app_process`.

The `*_COMMON` reference in `getInstance` is not a property of the SDK as a whole. Bytecode references across all 101 `android.hardware.bydauto.*` classes put it in exactly six devices — `ac`, `bodywork`, `doorlock`, `instrument`, `panorama`, `setting` — which is the same six-entry list the modified `PackageInstaller` whitelists. Every other device, including charging, gearbox and audio, constructs with no permission check at all.

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

`Utils.isBYDPermissions(String)` is called from `Utils.shouldShowPermission(AppPermissionGroup)`, which is the per-app permission filter of the installer's own screens. Decompiled from `PackageInstaller.odex`, the rule is:

```
if (app.uid <= 1000)                                     return false;   // system/root
if (group.isSystemFixed() && LocationUtils.isLocationGroupAndProvider(group.getName(), pkg))
                                                          return true;   // location providers
if (!"android".equals(group.getDeclaredPackage()))        return true;   // app-declared groups
if (Utils.isModernPermissionGroup(group.getName()))       return true;   // the nine AOSP groups
return Utils.isBYDPermissions(group.getName());                           // the whitelist above
```

`MODERN_PERMISSION_GROUPS` holds the stock nine (`CALENDAR`, `CAMERA`, `CONTACTS`, `LOCATION`, `MICROPHONE`, `PHONE`, `SENSORS`, `SMS`, `STORAGE`). A `BYDAUTO_*_COMMON` permission is declared by `"android"`, is not one of those nine, and is not in the six-name array it is compared against — so this filter is why exactly those six, and nothing else, reach the installer's screens.

### The Settings screen the driver actually uses is a different app

`/system` ships no AOSP `Settings` package. The car's settings UI is `priv-app/VehicleSettings` (`com.byd.vehiclesettings`), and its manifest owns `...system.mvp.view.app.PermissionActivity` plus `...system.application.InstalledAppDetailsActivity`. `VehicleSettings.odex` carries a full copy of the permission model under `com.byd.vehiclesettings.system.permission.*`, and **no** reference to `com.android.packageinstaller` at all.

Its copy has no BYD permission names anywhere in the dex, and its per-app list does not filter by name: `AppPermissions.loadPermissionGroups()` walks `packageInfo.requestedPermissions` in order and hands each entry to `AppPermissionGroup.create(context, packageInfo, permissionName)`. The only rejection is inside `create()` itself, on the permission's protection level and flags.

`com.byd.vehiclesettings.system.permission.utils.Utils.shouldShowPermission` does exist there, and it is the same shape as the installer's — but without the `isBYDPermissions` tail:

```
if (group.isSystemFixed() && !LocationUtils.isLocationGroupAndProvider(...)) return false;
if (!"android".equals(group.getDeclaringPackage()))                          return true;
if (Utils.isModernPermissionGroup(group.getName()))                          return true;
return false;
```

The screens that call it are `PermissionApps` (the granted/total counts), `PermissionStatusReceiver` and `PermissionAppsFragment` (permission → apps). The per-app permission list is *not* one of them.

**Corrected conclusion.** The six-name whitelist gates the *installer's* screens, not the driver's. The per-app screen the driver sees comes from `VehicleSettings`, which lists every permission the app requests. So a seventh, eighth and ninth `*_COMMON` declaration should appear in that same list next to the six, and the earlier claim that the other 14 are unreachable without ADB does not hold for this screen. That is still an inference from the code: the 2026-10-14 run below left the three new ones ungranted, so whether their toggles appeared is unrecorded — and per the measured result it no longer matters. `adb shell pm grant <pkg> android.permission.BYDAUTO_<name>_COMMON` remains the deterministic path and needs no whitelist.

## The permissions DiPlay requests, with labels from the firmware

Chinese labels/descriptions come from `framework-res.apk` resource strings. Permission names, `protectionLevel` and label resource ids were read straight out of `AndroidManifest.xml` inside `framework-res.apk` with `aapt2 dump xmltree`.

The first six are the ones confirmed togglable on the car. The last three were added on 2026-10-13; the firmware says they are equally `dangerous` and equally absent from the installer whitelist.

| Permission                        | 中文标题        | 中文说明                                     | English label               | On-car |
|-----------------------------------|-----------------|----------------------------------------------|-----------------------------|--------|
| `BYDAUTO_AC_COMMON`               | 空调信息        | 获得空调信息，包括空调温度等                 | Auto Ac Info                | granted |
| `BYDAUTO_BODYWORK_COMMON`         | 汽车车身信息    | 获得车身信息，包括车架号等                   | Auto Bodywork Info          | granted |
| `BYDAUTO_DOOR_LOCK_COMMON`        | 门锁信息        | 获得门锁信息，包括门锁状态等                 | Auto Doorlock Info          | granted |
| `BYDAUTO_INSTRUMENT_COMMON`       | 仪表信息        | 获得仪表信息，包括背光亮度等                 | Auto Instrument Info        | granted |
| `BYDAUTO_PANORAMA_COMMON`         | 全景、摄像头信息 | 获得全景、摄像头信息，包括旋转屏状态等       | Auto Panorama Info          | granted |
| `BYDAUTO_SETTING_COMMON`          | 车辆设置信息    | 获得车辆设置信息，包括转向助力模式设置等     | Auto SETTING Info           | granted |
| `BYDAUTO_CHARGING_COMMON`         | 充电信息        | 获得充电信息，包括充电器状态等               | Auto Charging Info          | new |
| `BYDAUTO_GEARBOX_COMMON`          | 变速箱信息      | 获得变速箱信息，包括手动挡实际档位等         | Auto Gearbox Info           | new |
| `BYDAUTO_AUDIO_COMMON`            | 音频信息        | 获得音频信息，包括当前音量值等               | Auto Audio Info             | new |

Every one of them is `protectionLevel = dangerous (0x1)`. Their `*_GET` and `*_SET` siblings are `signature (0x2)`.

### Two of the four "missing" permissions are dead ends

**`android.permission.BODY_SENSORS`** ("访问身体传感器（如心率监测器）") is stock AOSP, `dangerous`, and belongs to the `android.permission-group.SENSORS` group — so unlike the vendor permissions it does get a normal runtime dialog. It gates `SensorManager`'s body sensors (heart rate and friends). A head unit has none, and it has nothing to do with the BYD SDK. The HUD test build declares it purely as a control: it is the only requested permission that belongs to a permission group, so the driver's list is a direct read on how `VehicleSettings` treats grouped vs. group-less entries. It buys DiPlay nothing.

**BYD's own cabin sensors are not grantable at all.** The firmware declares `BYDAUTO_SENSOR_GET` and `BYDAUTO_SENSOR_SET` but **no `BYDAUTO_SENSOR_COMMON`**. `BYDAutoSensorDevice` (device type 1043) exposes temperature, humidity, light intensity and slope — useful data, but reachable only with the signature permission. There is no seventh permission to flip here.

## What the three new permissions expose — and why it is unreachable

The tables below describe what the SDK *would* return if its gates were open. They are not open: the measured run refuses all of it on `*_GET`. They are kept because the same device classes are worth revisiting on a DiLink version where BYD ships a signed-API mode, and because they are the map of what BYD's own privileged apps consume.

Method signatures below are declared methods recovered from `boot-framework.oat` in the VP128 image; all three device classes and their `AbsBYDAuto*Listener` counterparts live in the framework dex there. DiLink 1.0 ships no `BYDAutoFeatureIds`, so capability enumeration goes through `getType()`, `getFeatureList()` and — charging only — `hasFeature(String)`. The probe prints each class's full declared method list and the exact callback list; the tables below pick out what the data means.

### Charging — `BYDAUTO_DEVICE_CHARGING = 1009`

The largest read surface of the three. Zero-argument readers unless noted; `getChargingCapState(int)`, `getChargingTimerInfo(int)` and `getDischargeState(int)` take an index. The listener carries an `on*Changed` callback for each.

| Reader | Meaning |
|---|---|
| `getChargingState()` | READY / CHARGING / CHARG_FINISH / DISCHARG / CHARG_TERMINATE / BREAKDOWN |
| `getChargerWorkState()`, `getChargerFaultState()` | charger health |
| `getChargingGunState()` | gun connected: NONE / AC / DC / AC_DC / VTOL |
| `getChargingType()` | DEFAULT / AC / VTOG / GB_DC / GB_NON_DC |
| `getChargingPower(): double` | charging power |
| `getChargingCapacity(): double` | energy delivered |
| `getChargingRestTime(): int[]` | hours + minutes to full |
| `getChargingScheduleState/EnableState/Time()` | scheduled ("appointment") charging |
| `getChargeStopCapacityState()`, `getChargeStopSupportConfig()` | charge-limit setting |
| `getSmartChargingState()`, `getChargeTempCtlState()` | smart charging, charge temperature control |
| `getWirelessChargingState/SwitchState/Online5sState()` | wireless pad |
| `getDischargeState(int)`, `getDischargeRequestState()` | V2L / VTOL discharge |
| `getChargingTimerInfo(int)` | `ChargingTimerInfo` (switch, type, 24h hour/minute, per-weekday cycle) |

Writers (`setChargingMode`, `setChargeStopSwitchState`, `setWirelessChargingSwitchState`, `setChargingTimerInfo`, …) all return a `BYD_COMMAND_*` int and need `BYDAUTO_CHARGING_SET`.

### Gearbox — `BYDAUTO_DEVICE_GEARBOX = 1011`

Read-only: the class declares no state setter and no `*_SET_PERM` at all — the only one of the three that cannot write, even with a signature.

| Reader | Meaning |
|---|---|
| `getGearboxState()` | real lever position: D=0, R=1, N=2 |
| `getGearboxAutoModeType()` | P=1, R=2, N=3, D=4, M=5, S=6 |
| `getGearboxManualModeLevel()` | manual mode step |
| `getGearboxType()` | MT=0, AMT=1, AT=2, CVT=3, DCT=4 |
| `getGearboxCode()` | gearbox model string (`"6DT35"`, `"AF640"`, …) — the only String reader in the SDK |
| `getParkBrakeSwitch()`, `getEPBState()` | park brake, EPB (RELEASING/RELEASED/APPLYING/APPLIED/FAULT) |
| `getBrakePedalState()`, `getBrakeFluidLevel()` | brake input |
| `isInReverseGear()` | convenience boolean |

### Audio — `BYDAUTO_DEVICE_AUDIO = 1002`

Twenty read methods; `getEqualizer(int)` and `getKaraokeEqualizer(int)` take an index. Writers need `BYDAUTO_AUDIO_SET`.

| Reader | Meaning |
|---|---|
| `getVolume()`, `getVolumeSettingsource()`, `getVolumeCtrlMode()` | volume and who moved it (knob / wheel / PAD) |
| `getMuteState()`, `getNaviVolume()`, `getNaviMuteState()` | mute and navigation stream |
| `getChannel()` | RADIO / PAD / KARAOKE |
| `getSoundEffect()`, `getSoundField()[I`, `getEqualizer(int)` | tone and staging |
| `getLoudnessState()`, `getDiracLiveState()` | loudness, Dirac |
| `getKaraokeMode()` / `getKaraokeEqualizer(int)` | karaoke |
| `getIflytekFunctionMode()`, `getIflytekWorkMode()` | voice assistant mode |
| `getAudioMicAmpGain()`, `getLoudspeakerNum()` | mic gain, speaker count |

The one that matters for DiPlay is the first row: reading the head unit's own volume and mute is what lets CarPlay audio stop fighting the factory audio for the same knob. Setting volume is signature-gated, so DiPlay can observe but not steer.

### What DiPlay already reads without any of this

The value of these grants is narrower than the tables suggest, because DiPlay's HUD features already run over ADB (`service call autoservice <txn> i32 <device> i32 <feature>`), which needs no permission at all:

| Data | DiPlay path today |
|---|---|
| Charging state | [BydBattery.kt:53](shared/src/main/java/com/shilapi/xcertplay/hud/BydBattery.kt#L53) — device 1009 |
| State of charge, range, remaining kWh | [BydBattery.kt:33-48](shared/src/main/java/com/shilapi/xcertplay/hud/BydBattery.kt#L33-L48) — devices 1005, 1014 |
| Gear position | [BydWheelSpeed.kt:21](shared/src/main/java/com/shilapi/xcertplay/hud/BydWheelSpeed.kt#L21), [BydParkedState.kt:10](shared/src/main/java/com/shilapi/xcertplay/hud/BydParkedState.kt#L10) — device 1011 |
| Speed | [BydWheelSpeed.kt:20](shared/src/main/java/com/shilapi/xcertplay/hud/BydWheelSpeed.kt#L20) — device 1013 |
| Tyre pressure and temperatures | [BydTyres.kt:29-33](shared/src/main/java/com/shilapi/xcertplay/hud/BydTyres.kt#L29-L33) |

So charging and gearbox mostly *duplicate* reachable data, and the gains are the fields ADB does not reach (charge power, gun state, time-to-full, scheduled charging, wireless/discharge state; EPB, brake pedal, gearbox model) plus push callbacks instead of polling. Audio is genuinely new. Never at the cost of the ADB dependency: the same build uses it for everything else.

## What this does not change

- Writes to the instrument cluster, air conditioning, body etc. still require the signature `*_SET` permission at the `autoservice` boundary. DiPlay already handles instrument-cluster writes by invoking its own tool through ADB (`app_process` as the shell user), which bypasses the service-side check. Granting a `*_COMMON` permission does not open any new write path.
- Reads are gated twice, and `*_COMMON` satisfies neither gate on its own.

The first gate is in the SDK, on the caller's side. Each `BYDAutoXxxDevice` declares a `*_COMMON_PERM` constant next to `*_GET_PERM` / `*_SET_PERM`. The `*_COMMON` one is referenced only by `getInstance(Context)`, and only in the six classes listed above. Every accessor references its `*_GET` string instead — `getVolume()` and the other nineteen audio readers carry `android.permission.BYDAUTO_AUDIO_GET`, and the setters carry `*_SET`. **`registerListener` / `unregisterListener` also reference `*_GET` on every device class** (`BYDAutoPM2p5Device` is the lone `*_SET`), so there is no listener shortcut either: pushing data requires the same signature permission that reading it does. That is what the `getInstance` fallback in [BydClusterSong.kt:335](shared/src/main/java/com/shilapi/xcertplay/hud/BydClusterSong.kt#L335) works around for the instrument cluster.

The second gate is in the service. `libbydautoservice.so` implements `android::BYDAutoService::checkGetPermission` and `checkSetPermission` — the only two permission symbols in the binary, each logging `device type is %d` when it refuses. The ADB path passes because the check lets the shell user through, which is why `service call autoservice` works from DiPlay's HUD tooling without holding anything.

**What DiPlay should take from this: keep using the ADB path.** The SDK proxy is a dead end for a third-party app on this firmware — not because of the C service, but because the Java SDK refuses to make the call at all. Nothing in the tables above changes a line of DiPlay's vehicle-data code.

## Measured on the car (2026-10-14)

BYD Song Pro 2019, DiLink 1.0 (`Android/msm8953_64/msm8953_64:7.1.2/N2G47H/21:user/release-keys`), HUD test APK at uid 10009, all six original permissions granted and the three new ones not.

| Device | permission granted | `getInstance` | readers | threw | returned |
|---|---|---|---|---|---|
| AC (1000) | yes | ok | 31 | 29 | `getType`, `getFeatureList` |
| Bodywork (1001) | yes | ok | 15 | 13 | idem |
| Door lock (1041) | yes | ok | 2 | 0 | idem |
| Instrument (1007) | yes | ok | 17 | 15 | idem |
| Panorama (1031) | yes | ok | 19 | 17 | idem |
| Vehicle setting (1023) | yes | ok | 86 | 84 | idem |
| Charging (1009) | **no** | **ok** | 23 | 21 | idem |
| Gearbox (1011) | **no** | **ok** | 11 | 9 | idem |
| Audio (1002) | **no** | **ok** | 18 | 16 | idem |

Three things this settles:

1. **No read is reachable with `*_COMMON`.** Every failure is `SecurityException: Neither user 10009 nor current process has android.permission.BYDAUTO_<X>_GET` — raised inside the app's own process, before `autoservice` is called. The 18 readers that returned are the nine `getType()` (1000/1001/1041/1007/1031/1023/1009/1011/1002, matching the firmware constants) and the nine `getFeatureList()`, all `null`. `getFeatureList()` returning `null` rather than `int[]` also means the "capability list" is not a usable probe on this firmware.
2. **`getInstance` needs no permission for the three new categories.** They constructed fine while ungranted, exactly as the bytecode said they would — the `*_COMMON` check exists in six devices only. The earlier "baseline: `getInstance` throws without the permission" was wrong for every device outside that six.
3. **`queryAllStatus()` on the setting device is refused with `BYDAUTO_SETTING_SET`.** A `query`-shaped name that is really a write-side call; the probe's verb filter calls it and the SDK answers with the SET permission, which is the right outcome for a read-only probe.

Still open: whether the three new permissions and `BODY_SENSORS` appear as toggles in BYD Settings. The run above has them ungranted, so it does not say. Given point 1, the answer no longer changes anything for DiPlay.

The probe that produced this is `BydVehiclePermissionProbe` ([shared/.../BydVehiclePermissionProbe.kt](../shared/src/main/java/com/shilapi/xcertplay/hud/BydVehiclePermissionProbe.kt)), reached from the launcher activity in the debug APK. It enumerates each device's declared methods, the matching `AbsBYDAuto*Listener` callbacks, and calls every zero-argument reader it is safe to call. It performs no writes, and the run above is reproducible from the "Refresh" button plus the logcat tag `DiPlay-Byd-Perms`.

## Why BYD's own apps can read: they hold the platform signature

The obvious counter-example on this car is 百变主题, which reads vehicle state and works. It was checked against the VP128 image; the answer is the signature, not the permission tier.

| | 百变主题 / BYD market | DiPlay HUD test |
|---|---|---|
| package | `com.byd.multipletheme` (`/system/app/BydThemeStore`), `com.byd.appstore` (`/system/priv-app/BydAppStore`) | `com.shihab.diplay.hudtest` |
| BYDAUTO permissions | `BYDAUTO_BODYWORK_COMMON` + `_GET`, `BYDAUTO_GEARBOX_COMMON` + `_GET` (the app store adds energy/setting/speed) | the nine `*_COMMON` |
| `*_GET` (signature) | **held** | unobtainable |
| signing cert | `0C:9B:09:17:…:60:CE:6F` — byte-identical to `framework-res.apk` | DiPlay's own key |

`BYDAUTO_*_GET` is `protectionLevel=signature`: any package signed with the platform certificate holds it without a grant, which is exactly what a preinstalled BYD app is. The paired `*_COMMON` declarations are not what makes the read work — for the six whitelisted devices `*_COMMON` is only checked in `getInstance`, and gearbox is not one of those six (the measured run constructed it ungranted).

Two further notes from the same sweep, in case a future DiLink version is examined:

- **百变主题's logic is packed** (爱加密: `assets/ijiami.dat`, `ijiami.ajm`, `byd.dat`, `init.tgs` are all ciphertext; the APK's own `classes.dex` is a 7 KB loader with two obfuscated classes). Only its theme assets are readable, so its call sites cannot be inspected statically. The manifest is what proves it holds the GET tier.
- **BYD apps bundle their own copy of the vendor SDK.** `BydAppStore.odex` defines 139 `android.hardware.bydauto.*` classes rather than referencing the framework's, and calls `getInstance` / `registerListener` / `getChargingState` on them. Bundling sidesteps the caller-side check, not the service-side one — `autoservice` still decides by caller, which is why the shell user is the only identity DiPlay has that gets through.

## Comparison to Android 10 references

The public write-up at `wheregoes/byd-dolphin-hacking` describes the same `BYDAUTO_*` family on DiLink 4 / Android 10. The *names* carry across generations, but the car-side grant plumbing does not: on Android 10 BYD's permission UI lives in `PermissionController`, not `PackageInstaller`, and the whitelist and defaults are different. The six-permission whitelist above is specific to DiLink 1.0 and should not be extrapolated to newer DiLink versions without re-checking the installed `PackageInstaller`/`PermissionController`.
