package app.kinsight

import android.app.Activity
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowInsets
import android.widget.ScrollView
import android.widget.TextView
import app.kinsight.core.keystore.DeviceKeyProvider

/** Stage 3a diagnostic screen: which key backend this device uses and whether it passes the self-test. */
class DiagnosticsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = TextView(this).apply {
            textSize = 16f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            text = "Selecting key backend..."
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = ScrollView(this).apply { addView(text) }
        // targetSdk 35+ is edge-to-edge: keep the text clear of the system bars and cutout.
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(bars.left + pad, bars.top + pad, bars.right + pad, bars.bottom + pad)
            insets
        }
        setContentView(root)
        Thread {
            val selection = try {
                Result.success(DeviceKeyProvider.select(applicationContext))
            } catch (e: Exception) {
                Result.failure(e)
            }
            val device = "${Build.MANUFACTURER} ${Build.MODEL}, API ${Build.VERSION.SDK_INT}"
            val report = DiagnosticsReport.format(selection, device)
            Log.i(TAG, report)
            runOnUiThread { if (!isDestroyed) text.text = report }
        }.start()
    }

    private companion object {
        const val TAG = "Kinsight"
    }
}
