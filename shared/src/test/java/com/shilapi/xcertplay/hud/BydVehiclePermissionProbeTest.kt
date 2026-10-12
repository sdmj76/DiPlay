package com.shilapi.xcertplay.hud

import org.junit.Assert.assertEquals
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

    @Test fun everyCategoryOffersCandidatesForEveryLookup() {
        for (category in BydVehiclePermissionProbe.CATEGORIES) {
            assertTrue(
                "${category.label} must offer at least one feature-id catalog candidate",
                category.catalogNestedCandidates.isNotEmpty(),
            )
            assertTrue(
                "${category.label} must offer at least one device-constant candidate",
                category.deviceConstantCandidates.isNotEmpty(),
            )
            assertTrue(
                "${category.label} must offer at least one SDK device class candidate",
                category.deviceClassCandidates.isNotEmpty(),
            )
        }
    }

    @Test fun formatCoversEveryCategoryAndIsSelfIdentifying() {
        val fakes = BydVehiclePermissionProbe.CATEGORIES.map { category ->
            BydVehiclePermissionProbe.Result(
                category = category,
                granted = false,
                resolvedCatalog = null,
                resolvedDeviceConstant = null,
                deviceType = null,
                resolvedDeviceClass = null,
                deviceGetInstance = "class-not-found",
                readings = emptyList(),
                note = "off-car probe",
            )
        }
        val text = BydVehiclePermissionProbe.format(fakes, header = "unit-test")
        assertTrue("header is included", text.startsWith("unit-test"))
        for (category in BydVehiclePermissionProbe.CATEGORIES) {
            assertTrue(
                "format must mention category label ${category.label}",
                text.contains(category.label),
            )
            assertTrue(
                "format must mention permission ${category.permission}",
                text.contains(category.permission),
            )
        }
    }

    @Test fun formatRendersReadingsAndErrorsInHex() {
        val category = BydVehiclePermissionProbe.CATEGORIES.first()
        val result = BydVehiclePermissionProbe.Result(
            category = category,
            granted = true,
            resolvedCatalog = category.catalogNestedCandidates.first(),
            resolvedDeviceConstant = category.deviceConstantCandidates.first(),
            deviceType = 1003,
            resolvedDeviceClass = category.deviceClassCandidates.first(),
            deviceGetInstance = "ok",
            readings = listOf(
                BydVehiclePermissionProbe.Reading("AC_TEMP_GET", featureId = 0x12345678, value = 42L),
                BydVehiclePermissionProbe.Reading(
                    "AC_MISSING_GET",
                    featureId = 0x40C03032,
                    value = null,
                    error = "SecurityException: denied",
                ),
            ),
        )
        val text = BydVehiclePermissionProbe.format(listOf(result))
        assertTrue(
            "readable field prints its decimal value",
            text.contains("AC_TEMP_GET [0x12345678] = 42"),
        )
        assertTrue(
            "error path marks the field as ERR with the exception summary",
            text.contains("AC_MISSING_GET [0x40c03032] = ERR SecurityException: denied"),
        )
    }
}
