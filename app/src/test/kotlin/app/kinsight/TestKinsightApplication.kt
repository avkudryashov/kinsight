package app.kinsight

import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper

/**
 * The real application under Robolectric, which runs no startup providers and has no Android Keystore: WorkManager is
 * initialised by hand and a fake Keystore installed first.
 */
class TestKinsightApplication : KinsightApplication() {
    override fun onCreate() {
        FakeAndroidKeyStore.install()
        WorkManagerTestInitHelper.initializeTestWorkManager(this, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        super.onCreate()
    }
}
