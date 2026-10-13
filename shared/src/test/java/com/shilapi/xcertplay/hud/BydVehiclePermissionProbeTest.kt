package com.shilapi.xcertplay.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BydVehiclePermissionProbeTest {
    @Test fun probesTheSixWhitelistedPermissions() {
        val permissions = BydVehiclePermissionProbe.CATEGORIES.map { it.permission }
        assertEquals(
            listOf(
                "android.permission.BYDAUTO_AC_COMMON",
                "android.permission.BYDAUTO_BODYWORK_COMMON",
                "android.permission.BYDAUTO_DOOR_LOCK_COMMON",
                "android.permission.BYDAUTO_INSTRUMENT_COMMON",
                "android.permission.BYDAUTO_PANORAMA_COMMON",
                "android.permission.BYDAUTO_SETTING_COMMON",
            ),
            permissions,
        )
    }

    /** Class names were read out of VP128 (DiLink 1.0) firmware; a typo would silently probe nothing. */
    @Test fun usesTheClassNamesVerifiedAgainstDiLink1Firmware() {
        for (category in BydVehiclePermissionProbe.CATEGORIES) {
            assertTrue(
                "${category.label} device class must be a bydauto device",
                category.deviceClass.startsWith("android.hardware.bydauto.") &&
                    category.deviceClass.substringAfterLast('.').let {
                        it.startsWith("BYDAuto") && it.endsWith("Device")
                    },
            )
            assertTrue(
                "${category.label} listener class must be a bydauto Abs listener",
                category.listenerClass.startsWith("android.hardware.bydauto.") &&
                    category.listenerClass.substringAfterLast('.').let {
                        it.startsWith("AbsBYDAuto") && it.endsWith("Listener")
                    },
            )
            assertEquals(
                "${category.label} device and listener must share a subpackage",
                category.deviceClass.substringBeforeLast('.'),
                category.listenerClass.substringBeforeLast('.'),
            )
            assertTrue(
                "${category.label} device constant must be a BYDAUTO_DEVICE_* name",
                category.deviceConstant.startsWith("BYDAUTO_DEVICE_"),
            )
        }
    }

    /** The probe must never hand a value to the car: no writer may pass the reader filter. */
    @Test fun refusesEveryWriteShapedMethodName() {
        val writers = listOf(
            "setTemperature", "sendAutoNaviStatus", "setMediaState", "writeConfig",
            "startNavigation", "stopNavigation", "registerListener", "unregisterListener",
            "releaseDevice", "resetAll", "clearCache", "requestControl", "enableHud",
            "getAndSetMode", "isSetEnabled", "hasPendingWrite", "canResetCluster",
            "openDoor", "unlockDoor", "rebootUnit", "applyProfile", "notifyChange",
        )
        for (name in writers) {
            assertTrue(
                "$name must be refused as write-shaped",
                BydVehiclePermissionProbe.isWriteShapedForTest(name),
            )
        }
    }

    @Test fun acceptsPlainReaderNames() {
        val readers = listOf(
            "getTemperature", "getDoorLockState", "isAcOn", "hasRearCamera",
            "queryVin", "readOdometer", "supportHud", "getBacklightBrightness",
        )
        for (name in readers) {
            assertFalse(
                "$name must be accepted as a reader",
                BydVehiclePermissionProbe.isWriteShapedForTest(name),
            )
        }
    }

    @Test fun formatCoversEveryCategoryAndTheSharedSurface() {
        val shared = BydVehiclePermissionProbe.SharedSurface(
            managerMethods = listOf("getInstance(Context): BYDAutoDeviceManager"),
            baseDeviceMethods = listOf("getDeviceType(): int"),
            constants = listOf("BYDAUTO_DEVICE_AC" to 1000),
            featureIdsPresent = false,
        )
        val results = BydVehiclePermissionProbe.CATEGORIES.map { category ->
            BydVehiclePermissionProbe.Result(
                category = category,
                granted = true,
                deviceType = 1000,
                getInstance = "ok",
                deviceMethods = listOf("getFoo(): int"),
                listenerCallbacks = listOf("onFooChanged(int): void"),
                reads = listOf(BydVehiclePermissionProbe.ReadResult("getFoo(): int", "7")),
            )
        }
        val report = BydVehiclePermissionProbe.Report(shared, results)
        val text = BydVehiclePermissionProbe.format(report, header = "unit-test")

        assertTrue("header is included", text.startsWith("unit-test"))
        assertTrue("shared surface is reported", text.contains("SDK surface (shared)"))
        assertTrue(
            "the absent feature-id catalog is called out",
            text.contains("BYDAutoFeatureIds present: false"),
        )
        assertTrue("shared constants are listed", text.contains("BYDAUTO_DEVICE_AC = 1000"))
        for (category in BydVehiclePermissionProbe.CATEGORIES) {
            assertTrue("mentions ${category.label}", text.contains(category.label))
            assertTrue("mentions ${category.permission}", text.contains(category.permission))
            assertTrue("mentions ${category.deviceClass}", text.contains(category.deviceClass))
        }
        assertTrue("reader values are rendered", text.contains("getFoo(): int = 7"))
        assertTrue("listener callbacks are rendered", text.contains("onFooChanged(int): void"))
    }

    @Test fun formatMarksFailedReadersAsErrors() {
        val result = BydVehiclePermissionProbe.Result(
            category = BydVehiclePermissionProbe.CATEGORIES.first(),
            granted = false,
            deviceType = null,
            getInstance = "SecurityException: denied",
            deviceMethods = emptyList(),
            listenerCallbacks = emptyList(),
            reads = listOf(
                BydVehiclePermissionProbe.ReadResult(
                    "getTemperature(): int",
                    null,
                    error = "SecurityException: denied",
                )
            ),
            note = "getInstance refused; no reader was called",
        )
        val text = BydVehiclePermissionProbe.format(
            BydVehiclePermissionProbe.Report(
                BydVehiclePermissionProbe.SharedSurface(emptyList(), emptyList(), emptyList(), false),
                listOf(result),
            )
        )
        assertTrue(
            "failed reader is marked ERR",
            text.contains("getTemperature(): int = ERR SecurityException: denied"),
        )
        assertTrue("missing device constant is explicit", text.contains("(not found)"))
        assertTrue("the note is surfaced", text.contains("getInstance refused"))
    }
}
