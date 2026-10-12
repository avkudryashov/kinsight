package app.kinsight

import android.app.Activity
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.kinsight.core.keystore.DeviceIdentity
import app.kinsight.core.transport.ble.BlePermissions
import app.kinsight.core.transport.ble.GroupStoreException
import app.kinsight.core.transport.ble.SyncGraph
import app.kinsight.core.transport.ble.SyncService

/**
 * Diagnostics (technical, English only), opened from Settings: key backend, sync service state, «Sync now» (fast mode,
 * honours the user's Stop). It never starts the service by itself.
 */
class DiagnosticsActivity : Activity() {
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        output = TextView(this).apply {
            textSize = 16f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setText(R.string.diagnostics_loading)
        }
        val syncNow = Button(this).apply {
            setText(R.string.diagnostics_sync_now)
            setOnClickListener {
                startSync(fast = true)
                refresh()
            }
        }
        val refresh = Button(this).apply {
            setText(R.string.diagnostics_refresh)
            setOnClickListener { refresh() }
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(syncNow)
            addView(refresh)
            addView(output)
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = ScrollView(this).apply { addView(column) }
        // targetSdk 35+ is edge-to-edge: keep the content clear of the system bars and cutout.
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(bars.left + pad, bars.top + pad, bars.right + pad, bars.bottom + pad)
            insets
        }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh() // also after the user changed permissions or notification settings elsewhere
    }

    /** «Sync now» from diagnostics honours the user's Stop (stage 4): only the Start button clears it. */
    private fun startSync(fast: Boolean) {
        val app = applicationContext
        Thread { if (SyncGraph.groups(app).exists()) SyncService.start(app, SyncService.REASON_APP_OPEN, fast) }.start()
    }

    private fun refresh() {
        val app = applicationContext
        Thread {
            val selection = try {
                Result.success(DeviceIdentity.getBlocking(app))
            } catch (e: Exception) {
                Result.failure(e)
            }
            val device = "${Build.MANUFACTURER} ${Build.MODEL}, API ${Build.VERSION.SDK_INT}"
            val group = try {
                SyncGraph.groups(app).read()?.groupId?.hex
            } catch (e: GroupStoreException) {
                "unreadable (${e.message})"
            }
            val report = DiagnosticsReport.format(selection, device) + "\n" +
                DiagnosticsReport.transport(SyncGraph.snapshotNow(app), group, BlePermissions.missing(app))
            Log.i(TAG, report)
            runOnUiThread { if (!isDestroyed) output.text = report }
        }.start()
    }

    private companion object {
        const val TAG = "Kinsight"
    }
}
