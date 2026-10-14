package com.shilapi.xcertplay.hud

import android.content.Context
import android.content.pm.PackageManager
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Read probe for the nine BYDAUTO_*_COMMON vendor permissions the HUD test build requests on
 * DiLink 1.0. Six of them the driver grants from BYD Settings -> App -> Permissions and has
 * confirmed on the car; charging, gearbox and audio were added after the firmware audit showed the
 * same scheme covers them. See docs/BYD_VENDOR_PERMISSIONS.md for the audit behind both the list
 * and the class names.
 *
 * DiLink 1.0 ships no BYDAutoFeatureIds catalog (verified against VP128 firmware), so there is
 * no feature-id list to sweep the way DiLink 5.x allows. Instead the probe asks the installed SDK
 * to describe itself: it records each device class's method signatures, the matching
 * AbsBYDAuto*Listener callbacks the car can push, and the value returned by every zero-argument
 * reader it is safe to call.
 *
 * The probe performs no vehicle writes. Only zero-argument, non-void methods whose names begin
 * with a read verb and which pass [FORBIDDEN_PREFIX] are ever invoked.
 */
object BydVehiclePermissionProbe {
    /** One probed permission domain. Class names verified against VP128 (DiLink 1.0) firmware. */
    data class Category(
        val label: String,
        val permission: String,
        val deviceClass: String,
        val listenerClass: String,
        val deviceConstant: String,
    )

    val CATEGORIES: List<Category> = listOf(
        Category(
            label = "Air conditioning",
            permission = "android.permission.BYDAUTO_AC_COMMON",
            deviceClass = "android.hardware.bydauto.ac.BYDAutoAcDevice",
            listenerClass = "android.hardware.bydauto.ac.AbsBYDAutoAcListener",
            deviceConstant = "BYDAUTO_DEVICE_AC",
        ),
        Category(
            label = "Bodywork",
            permission = "android.permission.BYDAUTO_BODYWORK_COMMON",
            deviceClass = "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice",
            listenerClass = "android.hardware.bydauto.bodywork.AbsBYDAutoBodyworkListener",
            deviceConstant = "BYDAUTO_DEVICE_BODYWORK",
        ),
        Category(
            label = "Door lock",
            permission = "android.permission.BYDAUTO_DOOR_LOCK_COMMON",
            deviceClass = "android.hardware.bydauto.doorlock.BYDAutoDoorLockDevice",
            listenerClass = "android.hardware.bydauto.doorlock.AbsBYDAutoDoorLockListener",
            deviceConstant = "BYDAUTO_DEVICE_DOOR_LOCK",
        ),
        Category(
            label = "Instrument cluster",
            permission = "android.permission.BYDAUTO_INSTRUMENT_COMMON",
            deviceClass = "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice",
            listenerClass = "android.hardware.bydauto.instrument.AbsBYDAutoInstrumentListener",
            deviceConstant = "BYDAUTO_DEVICE_INSTRUMENT",
        ),
        Category(
            label = "Panorama / 360 camera",
            permission = "android.permission.BYDAUTO_PANORAMA_COMMON",
            deviceClass = "android.hardware.bydauto.panorama.BYDAutoPanoramaDevice",
            listenerClass = "android.hardware.bydauto.panorama.AbsBYDAutoPanoramaListener",
            deviceConstant = "BYDAUTO_DEVICE_PANORAMA",
        ),
        Category(
            label = "Vehicle setting",
            permission = "android.permission.BYDAUTO_SETTING_COMMON",
            deviceClass = "android.hardware.bydauto.setting.BYDAutoSettingDevice",
            listenerClass = "android.hardware.bydauto.setting.AbsBYDAutoSettingListener",
            deviceConstant = "BYDAUTO_DEVICE_SETTING",
        ),
        Category(
            label = "Charging",
            permission = "android.permission.BYDAUTO_CHARGING_COMMON",
            deviceClass = "android.hardware.bydauto.charging.BYDAutoChargingDevice",
            listenerClass = "android.hardware.bydauto.charging.AbsBYDAutoChargingListener",
            deviceConstant = "BYDAUTO_DEVICE_CHARGING",
        ),
        Category(
            label = "Gearbox",
            permission = "android.permission.BYDAUTO_GEARBOX_COMMON",
            deviceClass = "android.hardware.bydauto.gearbox.BYDAutoGearboxDevice",
            listenerClass = "android.hardware.bydauto.gearbox.AbsBYDAutoGearboxListener",
            deviceConstant = "BYDAUTO_DEVICE_GEARBOX",
        ),
        Category(
            label = "Audio",
            permission = "android.permission.BYDAUTO_AUDIO_COMMON",
            deviceClass = "android.hardware.bydauto.audio.BYDAutoAudioDevice",
            listenerClass = "android.hardware.bydauto.audio.AbsBYDAutoAudioListener",
            deviceConstant = "BYDAUTO_DEVICE_AUDIO",
        ),
    )

    /**
     * A stock Android runtime permission the debug manifest declares next to the vendor ones. No
     * BYD device sits behind it, so it gets no device section — it is reported so the driver's
     * permission list can be read across the one entry that belongs to a permission group and the
     * nine BYDAUTO_* ones that belong to none.
     */
    data class StockPermission(val label: String, val permission: String, val granted: Boolean = false)

    val STOCK_PERMISSIONS: List<StockPermission> = listOf(
        StockPermission("Body sensors", "android.permission.BODY_SENSORS"),
    )

    /** The result of invoking one zero-argument reader. */
    data class ReadResult(val call: String, val value: String?, val error: String? = null)

    data class Result(
        val category: Category,
        val granted: Boolean,
        val deviceType: Int?,
        val getInstance: String,
        val deviceMethods: List<String>,
        val listenerCallbacks: List<String>,
        val reads: List<ReadResult>,
        val note: String? = null,
    )

    /** The generic SDK layer shared by every device, dumped once. */
    data class SharedSurface(
        val managerMethods: List<String>,
        val baseDeviceMethods: List<String>,
        val constants: List<Pair<String, Int>>,
        val featureIdsPresent: Boolean,
    )

    data class Report(
        val shared: SharedSurface,
        val results: List<Result>,
        val stock: List<StockPermission> = emptyList(),
    )

    fun probe(context: Context): Report = Report(
        shared = probeSharedSurface(),
        results = CATEGORIES.map { probeCategory(context, it) },
        stock = STOCK_PERMISSIONS.map { it.copy(granted = isGranted(context, it.permission)) },
    )

    /** Multi-line, logcat-/clipboard-safe dump. Stable format so two runs diff cleanly. */
    fun format(report: Report, header: String? = null): String = buildString {
        header?.let { appendLine(it); appendLine() }

        append("== SDK surface (shared)\n")
        append("   BYDAutoFeatureIds present: ").append(report.shared.featureIdsPresent).append('\n')
        appendList("BYDAutoDeviceManager methods", report.shared.managerMethods)
        appendList("AbsBYDAutoDevice methods", report.shared.baseDeviceMethods)
        if (report.shared.constants.isEmpty()) {
            append("   BYDAutoConstants: (unreadable)\n")
        } else {
            append("   BYDAutoConstants (").append(report.shared.constants.size).append("):\n")
            for ((name, value) in report.shared.constants) {
                append("     ").append(name).append(" = ").append(value).append('\n')
            }
        }
        append('\n')

        for (r in report.results) {
            append("== ").append(r.category.label).append('\n')
            append("   permission: ").append(r.category.permission).append('\n')
            append("   granted:    ").append(r.granted).append('\n')
            append("   device:     ").append(r.category.deviceConstant).append(" = ")
                .append(r.deviceType?.toString() ?: "(not found)").append('\n')
            append("   sdk device: ").append(r.category.deviceClass)
                .append(" -> ").append(r.getInstance).append('\n')
            r.note?.let { append("   note:       ").append(it).append('\n') }
            appendList("methods", r.deviceMethods)
            appendList("listener callbacks", r.listenerCallbacks)
            if (r.reads.isEmpty()) {
                append("   reads: (no zero-argument reader was safe to call)\n")
            } else {
                append("   reads (").append(r.reads.size).append("):\n")
                for (read in r.reads) {
                    append("     ").append(read.call).append(" = ")
                    append(read.error?.let { "ERR $it" } ?: read.value)
                    append('\n')
                }
            }
            append('\n')
        }

        if (report.stock.isNotEmpty()) {
            append("== Stock Android permissions (control; no BYD device behind them)\n")
            for (entry in report.stock) {
                append("   ").append(entry.label).append(": ").append(entry.permission)
                    .append("  granted: ").append(entry.granted).append('\n')
            }
            append('\n')
        }
    }

    private fun StringBuilder.appendList(label: String, items: List<String>) {
        if (items.isEmpty()) {
            append("   ").append(label).append(": (none)\n")
            return
        }
        append("   ").append(label).append(" (").append(items.size).append("):\n")
        for (item in items) append("     ").append(item).append('\n')
    }

    private fun probeSharedSurface(): SharedSurface {
        val manager = loadClass("android.hardware.bydauto.BYDAutoDeviceManager")
        val base = loadClass("android.hardware.bydauto.AbsBYDAutoDevice")
        val constantsClass = loadClass("android.hardware.bydauto.BYDAutoConstants")
        return SharedSurface(
            managerMethods = manager?.let(::describeMethods).orEmpty(),
            baseDeviceMethods = base?.let(::describeMethods).orEmpty(),
            constants = constantsClass?.let(::readIntConstants).orEmpty(),
            featureIdsPresent = loadClass("android.hardware.bydauto.BYDAutoFeatureIds") != null,
        )
    }

    private fun probeCategory(context: Context, category: Category): Result {
        val granted = isGranted(context, category.permission)
        val deviceType = loadClass("android.hardware.bydauto.BYDAutoConstants")
            ?.let { readIntField(it, category.deviceConstant) }
        val deviceClass = loadClass(category.deviceClass)
        val listenerClass = loadClass(category.listenerClass)

        if (deviceClass == null) {
            return Result(
                category = category,
                granted = granted,
                deviceType = deviceType,
                getInstance = "class-not-found",
                deviceMethods = emptyList(),
                listenerCallbacks = listenerClass?.let(::describeMethods).orEmpty(),
                reads = emptyList(),
                note = "device class absent on this firmware",
            )
        }

        val instance = try {
            deviceClass.getMethod("getInstance", Context::class.java).invoke(null, context)
        } catch (error: Throwable) {
            return Result(
                category = category,
                granted = granted,
                deviceType = deviceType,
                getInstance = describe(error),
                deviceMethods = describeMethods(deviceClass),
                listenerCallbacks = listenerClass?.let(::describeMethods).orEmpty(),
                reads = emptyList(),
                note = "getInstance refused; no reader was called",
            )
        }

        return Result(
            category = category,
            granted = granted,
            deviceType = deviceType,
            getInstance = if (instance == null) "null" else "ok",
            deviceMethods = describeMethods(deviceClass),
            listenerCallbacks = listenerClass?.let(::describeMethods).orEmpty(),
            reads = if (instance == null) emptyList() else invokeSafeReaders(deviceClass, instance),
            note = if (instance == null) "getInstance returned null; no reader was called" else null,
        )
    }

    /**
     * Invokes every zero-argument reader on [instance]. A method qualifies only when it takes no
     * argument (so no value can be handed to the car), returns something, and its name begins with
     * a read verb that is not on [FORBIDDEN_PREFIX].
     */
    private fun invokeSafeReaders(type: Class<*>, instance: Any): List<ReadResult> =
        readableMethods(type).map { method ->
            val call = "${method.name}(): ${simpleName(method.returnType)}"
            try {
                ReadResult(call, render(method.invoke(instance)))
            } catch (error: Throwable) {
                ReadResult(call, null, describe(error))
            }
        }

    private fun readableMethods(type: Class<*>): List<Method> = runCatching {
        type.methods
            .filter { it.declaringClass != Any::class.java }
            .filter { it.parameterCount == 0 }
            .filter { it.returnType != Void.TYPE }
            .filter { !Modifier.isStatic(it.modifiers) }
            .filter { method -> READ_PREFIX.any { method.name.startsWith(it) } }
            .filterNot { isWriteShaped(it.name) }
            .distinctBy { it.name }
            .sortedBy { it.name }
    }.getOrDefault(emptyList())

    private fun describeMethods(type: Class<*>): List<String> = runCatching {
        type.declaredMethods
            .filterNot { it.isSynthetic }
            .map { method ->
                val args = method.parameterTypes.joinToString(", ", transform = ::simpleName)
                val prefix = if (Modifier.isStatic(method.modifiers)) "static " else ""
                "$prefix${method.name}($args): ${simpleName(method.returnType)}"
            }
            .distinct()
            .sorted()
    }.getOrDefault(emptyList())

    private fun readIntConstants(type: Class<*>): List<Pair<String, Int>> = runCatching {
        type.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
            .mapNotNull { field ->
                field.isAccessible = true
                runCatching { field.name to field.getInt(null) }.getOrNull()
            }
            .sortedBy { it.first }
    }.getOrDefault(emptyList())

    private fun readIntField(type: Class<*>, name: String): Int? = runCatching {
        val field = type.getField(name)
        if (!Modifier.isStatic(field.modifiers) || field.type != Int::class.javaPrimitiveType) {
            null
        } else {
            field.getInt(null)
        }
    }.getOrNull()

    private fun render(value: Any?): String = when (value) {
        null -> "null"
        is IntArray -> value.joinToString(prefix = "[", postfix = "]")
        is LongArray -> value.joinToString(prefix = "[", postfix = "]")
        is ByteArray -> value.joinToString(" ", "[", "]") { "%02x".format(it) }.take(MAX_VALUE_CHARS)
        is BooleanArray -> value.joinToString(prefix = "[", postfix = "]")
        is Array<*> -> value.joinToString(prefix = "[", postfix = "]").take(MAX_VALUE_CHARS)
        else -> value.toString().take(MAX_VALUE_CHARS)
    }

    private fun loadClass(name: String): Class<*>? = runCatching { Class.forName(name) }.getOrNull()

    private fun simpleName(type: Class<*>): String = type.simpleName.ifBlank { type.name }

    private fun isGranted(context: Context, perm: String): Boolean =
        context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    private fun describe(error: Throwable): String {
        val root = (error as? InvocationTargetException)?.targetException ?: error
        val name = root.javaClass.simpleName.ifEmpty { root.javaClass.name }
        return name + (root.message?.let { ": $it" }.orEmpty())
    }

    private const val MAX_VALUE_CHARS = 200

    private val READ_PREFIX = listOf("get", "is", "has", "query", "read", "fetch", "can", "support")

    /**
     * Anything that could hand a value to the car, change SDK state, or tear down a device.
     * Checked against the whole method name, so `getAndSet…` style names are refused too.
     */
    /**
     * True when [name] looks like it changes something rather than reports it, so the probe must
     * not invoke it. Mutating verbs are matched as whole camelCase words: a substring test would
     * reject `getDoorLockState`, whose "Lock" is part of this SDK's own device noun.
     */
    private fun isWriteShaped(name: String): Boolean {
        val words = camelWords(name)
        val leading = words.firstOrNull() ?: return true
        if (leading in MUTATING_VERB) return true
        val rest = if (leading in READ_PREFIX) words.drop(1) else words
        return rest.any { it in MUTATING_VERB && it !in NOUN_NOT_VERB }
    }

    /** Lets the test pin the write-shape filter down without a car or the vendor SDK. */
    internal fun isWriteShapedForTest(name: String): Boolean = isWriteShaped(name)

    private fun camelWords(name: String): List<String> =
        CAMEL_WORD.findAll(name).map { it.value.lowercase() }.toList()

    private val CAMEL_WORD = Regex("[A-Z]+(?![a-z])|[A-Z][a-z]*|[a-z]+|[0-9]+")

    /** Verbs that change the car or the SDK. A name beginning with one is never invoked. */
    private val MUTATING_VERB = setOf(
        "set", "send", "write", "start", "stop", "open", "close", "enable", "disable",
        "register", "unregister", "subscribe", "unsubscribe", "release", "destroy", "reset",
        "clear", "remove", "add", "request", "execute", "control", "notify", "post", "update",
        "init", "create", "connect", "disconnect", "login", "logout", "switch", "toggle",
        "apply", "commit", "save", "delete", "put", "trigger", "activate", "lock", "unlock",
        "exit", "finish", "cancel", "abort", "kill", "shutdown", "reboot", "restart",
        "install", "upgrade", "format", "erase", "wipe",
    )

    /**
     * Words that collide with a mutating verb but read as nouns inside this SDK: "DoorLock" is a
     * device name (`android.hardware.bydauto.doorlock`). A name that *begins* with lock/unlock is
     * still refused by the leading-verb check above.
     */
    private val NOUN_NOT_VERB = setOf("lock", "unlock")
}
