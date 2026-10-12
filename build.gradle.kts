plugins {
    // Loads build-logic (AGP + KGP) into the root classloader next to Wire, which also pulls in KGP.
    // Otherwise KGP is loaded by Wire's classloader, which cannot see AGP (NoClassDefFoundError BaseVariant).
    id("kinsight.android.library") apply false
    alias(libs.plugins.wire) apply false
}
