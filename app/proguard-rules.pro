# Release shrinking (R8). Libraries ship their own consumer rules (Room, Wire, Glance, CameraX, Compose);
# only what they do not cover is here.

# BouncyCastle is used through its lightweight API (core/crypto), no JCE provider registration, so nothing
# is looked up by name. Optional parts reference JDK classes Android lacks.
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
-dontwarn java.lang.management.**

# Wire-generated messages keep their adapters through Wire's consumer rules; the companion ADAPTER fields are
# read reflectively by ProtoAdapter.get(Class), used by the sync codec.
-keepclassmembers class ** extends com.squareup.wire.Message {
    public static final com.squareup.wire.ProtoAdapter ADAPTER;
}

# Crash reports stay readable without a server: keep the line numbers, hide the file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
