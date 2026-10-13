package com.shilapi.xcertplay.hud

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * HUD-test entry point for the BYD vendor permission probe. Launcher-visible only in the debug
 * (.hudtest) build. Reads every feature id advertised under BYDAutoFeatureIds$<Nested> for the
 * six BYDAUTO_*_COMMON permissions and prints the results on screen so the driver can verify
 * which categories actually return data on their car.
 *
 * The activity performs no vehicle writes. See docs/BYD_VENDOR_PERMISSIONS.md.
 */
class BydVehiclePermissionsProbeActivity : Activity() {
    private lateinit var output: TextView
    private lateinit var summary: TextView

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (!packageName.endsWith(".hudtest")) {
            // Hard safety net; the debug manifest already keeps this out of release.
            finish()
            return
        }

        output = TextView(this).apply {
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            setPadding(16, 16, 16, 16)
            movementMethod = ScrollingMovementMethod()
        }
        summary = TextView(this).apply {
            textSize = 14f
            setPadding(16, 8, 16, 8)
            gravity = Gravity.CENTER_VERTICAL
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 8, 8, 8)
            addView(button("Refresh") { refresh() })
            addView(button("Copy") { copyOutput() })
            addView(button("App info") {
                try {
                    startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(android.net.Uri.fromParts("package", packageName, null))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (error: Exception) {
                    Toast.makeText(this@BydVehiclePermissionsProbeActivity,
                        "Open Settings manually: ${error.message}", Toast.LENGTH_LONG).show()
                }
            })
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                TextView(this@BydVehiclePermissionsProbeActivity).apply {
                    text = "BYD Vehicle Permissions Probe"
                    textSize = 18f
                    setPadding(16, 16, 16, 4)
                }
            )
            addView(
                TextView(this@BydVehiclePermissionsProbeActivity).apply {
                    text = "Read-only: lists the installed BYD SDK surface and calls only " +
                        "zero-argument readers. Flip the six BYDAUTO_*_COMMON toggles in BYD " +
                        "Settings → App → DiPlay HUD Test → Permissions, then tap Refresh."
                    textSize = 12f
                    setPadding(16, 0, 16, 8)
                }
            )
            addView(summary)
            addView(buttons)
            addView(ScrollView(this@BydVehiclePermissionsProbeActivity).apply {
                addView(output)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            ))
        })

        refresh()
    }

    private fun button(label: String, click: () -> Unit): Button = Button(this).apply {
        text = label
        setOnClickListener { click() }
    }

    private fun refresh() {
        output.text = "Reading…"
        summary.text = ""
        try {
            val report = BydVehiclePermissionProbe.probe(this)
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val header = "DiPlay HUD-test vehicle permission probe\n" +
                "ts=$stamp pkg=$packageName uid=${android.os.Process.myUid()}\n" +
                "build=${android.os.Build.FINGERPRINT}"
            val text = BydVehiclePermissionProbe.format(report, header)
            output.text = text
            val results = report.results
            val granted = results.count { it.granted }
            val sdkOk = results.count { it.getInstance == "ok" }
            val values = results.sumOf { result -> result.reads.count { it.error == null } }
            val reads = results.sumOf { it.reads.size }
            summary.text = "Granted $granted/${results.size} • getInstance ok $sdkOk/${results.size} • " +
                "readers $values/$reads returned a value"
            Log.i(TAG, text)
        } catch (error: Throwable) {
            output.text = "Probe failed: ${error.javaClass.simpleName}: ${error.message}"
            Log.e(TAG, "probe failed", error)
        }
    }

    private fun copyOutput() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = output.text?.toString().orEmpty()
        if (clipboard == null || text.isEmpty()) {
            Toast.makeText(this, "Nothing to copy", Toast.LENGTH_SHORT).show()
            return
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("BYD permissions probe", text))
        Toast.makeText(this, "Copied ${text.length} chars", Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "DiPlay-Byd-Perms"
    }
}
