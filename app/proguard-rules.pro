# ── Kitaab R8 rules ─────────────────────────────────────────────────────────

# Keep line numbers so crash traces stay readable
-keepattributes SourceFile,LineNumberTable,Signature,*Annotation*,InnerClasses,EnclosingMethod
-renamesourcefileattribute SourceFile

# Google API client / Drive (reflection-based JSON models)
-keep class com.google.api.** { *; }
-keep class com.google.api.client.** { *; }
-keepclassmembers class * {
    @com.google.api.client.util.Key <fields>;
}
-dontwarn com.google.api.client.**
-dontwarn org.apache.http.**
-dontwarn javax.naming.**
-dontwarn org.ietf.jgss.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# Gson
-keep class com.google.gson.** { *; }
-keepattributes EnclosingMethod

# Google Sign-In
-keep class com.google.android.gms.auth.** { *; }

# Firebase Realtime Database
-keep class com.google.firebase.database.** { *; }
-keepclassmembers class com.pdfreader.app.** { public <init>(); }

# PDF viewer (pdfium JNI)
-keep class com.shockwave.pdfium.** { *; }
-keep class com.github.barteksc.pdfviewer.** { *; }

# Room entities & WorkManager workers (instantiated by name)
-keep class com.pdfreader.app.data.model.** { *; }
-keep class * extends androidx.work.ListenableWorker { <init>(...); }

# Kotlin coroutines
-dontwarn kotlinx.coroutines.**

# Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { <init>(...); }

# PdfBox-Android (edit pages before upload)
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.harmony.** { *; }
-dontwarn com.tom_roush.pdfbox.**
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
