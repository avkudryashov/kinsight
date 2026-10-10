package app.kinsight

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
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
 * Stage 3 diagnostic screen (technical, English only; replaced in stage 4): key backend, sync service state, and the
 * UI start of the service (spec §8 «Запуск из интерфейса») with a «Sync now» button for the fast mode. Every start
 * from here passes SyncService.REASON_UI, so it also clears a Stop the user pressed in the notification.
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
        // A configuration change is not a new start from the UI.
        if (savedInstanceState != null) return
        val missing = wantedPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS) // the start follows in the result
        } else {
            startSync(fast = false)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh() // also after the user changed permissions or notification settings elsewhere
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        startSync(fast = false) // SyncService.start itself refuses without the «Nearby devices» permissions
        refresh()
    }

    /** «Nearby devices» for the radio, and (API 33+) notifications so the user can see and stop the service. */
    private fun wantedPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= 33) BlePermissions.REQUIRED + Manifest.permission.POST_NOTIFICATIONS else BlePermissions.REQUIRED

    /** No group (stage 4 pairing) means nothing to run: do not flash a notification for a service that stops at once. */
    private fun startSync(fast: Boolean) {
        if (SyncGraph.groups(this).exists()) SyncService.start(this, SyncService.REASON_UI, fast)
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
        const val REQUEST_PERMISSIONS = 1
    }
}
