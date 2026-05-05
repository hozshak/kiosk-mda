# Keep WebView JavaScript Interface methods
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep Device Admin Receiver
-keep class com.kiosk.mda.admin.** { *; }

# Kotlin
-keep class kotlin.Metadata { *; }
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** { volatile <fields>; }

# Google Tink (transitive von androidx.security:security-crypto)
-dontwarn javax.annotation.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.api.client.**
-keep class com.google.crypto.tink.** { *; }
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations

# OkHttp / Conscrypt (manchmal von WebView referenziert)
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
