package app.kinsight.feature.onboarding

import android.content.Context
import android.util.Log
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/**
 * Asks Google Play services for the code scanner's UI module as soon as the first run opens, so the first «Scan» does
 * not fail while the module is still downloading (the manifest's ML Kit DEPENDENCIES meta-data asks at install
 * time too). Best effort: no Play services, no network or any other failure is logged and the scan screen still
 * reports SCANNER_UNAVAILABLE on its own.
 */
internal fun requestScannerModule(context: Context) {
    try {
        val request = ModuleInstallRequest.newBuilder().addApi(GmsBarcodeScanning.getClient(context)).build()
        ModuleInstall.getClient(context).installModules(request)
            .addOnSuccessListener { Log.i(TAG, "scanner module: already installed=${it.areModulesAlreadyInstalled()}") }
            .addOnFailureListener { Log.w(TAG, "scanner module request failed", it) }
    } catch (e: Throwable) {
        Log.w(TAG, "scanner module request failed", e)
    }
}

private const val TAG = "KinsightOnboarding"
