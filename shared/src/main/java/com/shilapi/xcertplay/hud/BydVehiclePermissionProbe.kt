package com.shilapi.xcertplay.hud

import android.content.Context
import android.content.pm.PackageManager
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier

/**
 * Read probe for the six BYDAUTO_*_COMMON vendor permissions BYD's modified PackageInstaller
 * on DiLink 1.0 lets a driver grant from Settings -> App -> Permissions. See
 * docs/BYD_VENDOR_PERMISSIONS.md for the firmware audit that identified these six.
 *
 * For each category the probe records whether the permission is granted on this install,
 * whether the matching SDK device class can be built with [getInstance] (the SDK's own
 * caller-side permission check), and the value every advertised feature id under the matching
 * BYDAutoFeatureIds$<Nested> nested class returns from BYDAutoDeviceManager.getInt.
 *
 * The probe performs no vehicle writes.
 */
object BydVehiclePermissionProbe {
    /**
     * One probed permission domain. Several candidate class/constant names cover minor BYD
     * firmware naming drift; the first that resolves is reported in the result.
     */
    data class Category(
        val label: String,
        val permission: String,
        /** Candidates for BYDAutoFeatureIds$<Nested>. */
        val catalogNestedCandidates: List<String>,
        /** Candidates for the BYDAutoConstants.BYDAUTO_DEVICE_<X> field name. */
        val deviceConstantCandidates: List<String>,
        /** Candidates for the fully-qualified BYDAutoXxxDevice class name. */
        val deviceClassCandidates: List<String>,
    )

    val CATEGORIES: List<Category> = listOf(
        Category(
            label = "Air conditioning",
            permission = "android.permission.BYDAUTO_AC_COMMON",
            catalogNestedCandidates = listOf("AC", "Ac", "AirCondition", "AirConditioner"),
            deviceConstantCandidates = listOf("BYDAUTO_DEVICE_AC", "BYDAUTO_DEVICE_AIRCONDITION"),
            deviceClassCandidates = listOf(
                "android.hardware.bydauto.ac.BYDAutoAcDevice",
                "android.hardware.bydauto.aircondition.BYDAutoAirConditionDevice",
            ),
        ),
        Category(
            label = "Bodywork",
            permission = "android.permission.BYDAUTO_BODYWORK_COMMON",
            catalogNestedCandidates = listOf("Bodywork", "BodyWork"),
            deviceConstantCandidates = listOf("BYDAUTO_DEVICE_BODYWORK"),
            deviceClassCandidates = listOf("android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice"),
        ),
        Category(
            label = "Door lock",
            permission = "android.permission.BYDAUTO_DOOR_LOCK_COMMON",
            catalogNestedCandidates = listOf("DoorLock", "Doorlock"),
            deviceConstantCandidates = listOf("BYDAUTO_DEVICE_DOORLOCK", "BYDAUTO_DEVICE_DOOR_LOCK"),
            deviceClassCandidates = listOf(
                "android.hardware.bydauto.doorlock.BYDAutoDoorLockDevice",
                "android.hardware.bydauto.doorlock.BYDAutoDoorlockDevice",
            ),
        ),
        Category(
            label = "Instrument cluster",
            permission = "android.permission.BYDAUTO_INSTRUMENT_COMMON",
            catalogNestedCandidates = listOf("Instrument"),
            deviceConstantCandidates = listOf("BYDAUTO_DEVICE_INSTRUMENT"),
            deviceClassCandidates = listOf("android.hardware.bydauto.instrument.BYDAutoInstrumentDevice"),
        ),
        Category(
            label = "Panorama / 360 camera",
            permission = "android.permission.BYDAUTO_PANORAMA_COMMON",
            catalogNestedCandidates = listOf("Panorama"),
            deviceConstantCandidates = listOf("BYDAUTO_DEVICE_PANORAMA"),
            deviceClassCandidates = listOf("android.hardware.bydauto.panorama.BYDAutoPanoramaDevice"),
        ),
        Category(
            label = "Vehicle setting",
            permission = "android.permission.BYDAUTO_SETTING_COMMON",
            catalogNestedCandidates = listOf("Setting"),
            deviceConstantCandidates = listOf("BYDAUTO_DEVICE_SETTING"),
            deviceClassCandidates = listOf("android.hardware.bydauto.setting.BYDAutoSettingDevice"),
        ),
    )

    data class Reading(
        val fieldName: String,
        val featureId: Int,
        val value: Long?,
        val error: String? = null,
    )

    data class Result(
        val category: Category,
        val granted: Boolean,
        val resolvedCatalog: String?,
        val resolvedDeviceConstant: String?,
        val deviceType: Int?,
        val resolvedDeviceClass: String?,
        val deviceGetInstance: String,
        val readings: List<Reading>,
        val note: String? = null,
    )

    /** Runs the probe against the current process context. Does not mutate vehicle state. */
    fun probe(context: Context): List<Result> {
        val manager = runCatching { reflectManager(context) }.getOrNull()
        val constantsClass = runCatching {
            Class.forName("android.hardware.bydauto.BYDAutoConstants")
        }.getOrNull()
        return CATEGORIES.map { category ->
            probeCategory(context, category, manager, constantsClass)
        }
    }

    /** Multi-line, human-readable, logcat-/clipboard-safe dump of [results]. */
    fun format(results: List<Result>, header: String? = null): String = buildString {
        header?.let { appendLine(it); appendLine() }
        for (r in results) {
            append("== ").append(r.category.label).append('\n')
            append("   permission: ").append(r.category.permission).append('\n')
            append("   granted:    ").append(r.granted).append('\n')
            append("   catalog:    ")
                .append(r.resolvedCatalog ?: "(none of ${r.category.catalogNestedCandidates})")
                .append('\n')
            append("   device:     ")
            if (r.resolvedDeviceConstant != null && r.deviceType != null) {
                append(r.resolvedDeviceConstant).append(" = ").append(r.deviceType)
            } else {
                append("(none of ${r.category.deviceConstantCandidates})")
            }
            append('\n')
            append("   sdk device: ")
                .append(r.resolvedDeviceClass ?: "(none of ${r.category.deviceClassCandidates})")
                .append(" -> ").append(r.deviceGetInstance).append('\n')
            r.note?.let { append("   note:       ").append(it).append('\n') }
            if (r.readings.isEmpty()) {
                append("   (no feature ids read)\n")
            } else {
                append("   feature ids (").append(r.readings.size).append("):\n")
                for (reading in r.readings) {
                    append("     ").append(reading.fieldName)
                        .append(" [0x").append(hex(reading.featureId)).append("] = ")
                    when {
                        reading.error != null -> append("ERR ").append(reading.error)
                        else -> append(reading.value)
                    }
                    append('\n')
                }
            }
            append('\n')
        }
    }

    private fun probeCategory(
        context: Context,
        category: Category,
        manager: ManagerHandle?,
        constantsClass: Class<*>?,
    ): Result {
        val granted = isGranted(context, category.permission)
        val catalog = resolveFirst(category.catalogNestedCandidates) { name ->
            Class.forName("android.hardware.bydauto.BYDAutoFeatureIds\$$name")
        }
        val device = resolveConstant(constantsClass, category.deviceConstantCandidates)
        val (deviceClass, deviceClassName) = resolveDeviceClass(category.deviceClassCandidates)
        val sdkOutcome = probeGetInstance(deviceClass, context)
        val readings = buildReadings(manager, catalog?.second, device?.second)
        val note = when {
            manager == null -> "BYDAutoDeviceManager unavailable; feature ids not read"
            catalog == null -> "feature-id catalog nested class not found; feature ids not read"
            device == null -> "BYDAUTO_DEVICE_* constant not found; feature ids not read"
            else -> null
        }
        return Result(
            category = category,
            granted = granted,
            resolvedCatalog = catalog?.first,
            resolvedDeviceConstant = device?.first,
            deviceType = device?.second,
            resolvedDeviceClass = deviceClassName,
            deviceGetInstance = sdkOutcome,
            readings = readings,
            note = note,
        )
    }

    private fun probeGetInstance(deviceClass: Class<*>?, context: Context): String {
        if (deviceClass == null) return "class-not-found"
        return try {
            val method = deviceClass.getMethod("getInstance", Context::class.java)
            val instance = method.invoke(null, context)
            if (instance == null) "null" else "ok"
        } catch (error: Throwable) {
            describe(error)
        }
    }

    private fun buildReadings(
        manager: ManagerHandle?,
        catalogClass: Class<*>?,
        deviceType: Int?,
    ): List<Reading> {
        if (manager == null || catalogClass == null || deviceType == null) return emptyList()
        val readings = ArrayList<Reading>()
        for (field in catalogClass.declaredFields) {
            if (!Modifier.isStatic(field.modifiers)) continue
            val isInt = field.type == Int::class.javaPrimitiveType
            val isLong = field.type == Long::class.javaPrimitiveType
            if (!isInt && !isLong) continue
            // _SET fields are write-shaped; probing them as reads is noisy. Keep _GET and plain names.
            if (field.name.endsWith("_SET") && !field.name.endsWith("_GET_SET")) continue
            field.isAccessible = true
            val featureId = when {
                isInt -> field.getInt(null)
                else -> field.getLong(null).toInt()
            }
            val read = runCatching { manager.getInt(deviceType, featureId) }
            readings.add(
                if (read.isSuccess) {
                    Reading(field.name, featureId, read.getOrNull()?.toLong())
                } else {
                    Reading(field.name, featureId, null, error = describe(read.exceptionOrNull()!!))
                }
            )
        }
        return readings.sortedBy { it.fieldName }
    }

    private fun resolveConstant(
        constantsClass: Class<*>?,
        candidates: List<String>,
    ): Pair<String, Int>? {
        constantsClass ?: return null
        for (name in candidates) {
            val value = runCatching {
                val field = constantsClass.getField(name)
                if (!Modifier.isStatic(field.modifiers) || field.type != Int::class.javaPrimitiveType) {
                    null
                } else {
                    field.getInt(null)
                }
            }.getOrNull()
            if (value != null) return name to value
        }
        return null
    }

    private fun resolveDeviceClass(candidates: List<String>): Pair<Class<*>?, String?> {
        for (name in candidates) {
            val cls = runCatching { Class.forName(name) }.getOrNull()
            if (cls != null) return cls to name
        }
        return null to null
    }

    private fun <T : Any> resolveFirst(candidates: List<String>, loader: (String) -> T): Pair<String, T>? {
        for (name in candidates) {
            val found = runCatching { loader(name) }.getOrNull()
            if (found != null) return name to found
        }
        return null
    }

    private fun reflectManager(context: Context): ManagerHandle {
        val managerClass = Class.forName("android.hardware.bydauto.BYDAutoDeviceManager")
        val instance = managerClass.getMethod("getInstance", Context::class.java)
            .invoke(null, context) ?: error("BYDAutoDeviceManager.getInstance returned null")
        val getInt = managerClass.getMethod(
            "getInt",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
        return ManagerHandle(instance, getInt)
    }

    private class ManagerHandle(
        private val instance: Any,
        private val getInt: java.lang.reflect.Method,
    ) {
        fun getInt(type: Int, fid: Int): Int =
            (getInt.invoke(instance, type, fid) as Number).toInt()
    }

    private fun isGranted(context: Context, perm: String): Boolean =
        context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    private fun describe(error: Throwable): String {
        val root = (error as? InvocationTargetException)?.targetException ?: error
        val name = root.javaClass.simpleName.ifEmpty { root.javaClass.name }
        return name + (root.message?.let { ": $it" }.orEmpty())
    }

    private fun hex(value: Int): String =
        value.toLong().and(0xFFFFFFFFL).toString(16)
}
