# ProGuard / R8 rules for Voice Recorder & Secure Vault

# Keep all AndroidManifest entry points
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends androidx.core.content.FileProvider

# Keep custom Views referenced in XML layouts
-keep class com.my.seekapplication.PatternLockView { *; }
-keep class com.my.seekapplication.ZoomableImageView { *; }
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public void set*(...);
}

# Keep RecyclerView ViewHolders and Adapters
-keep class * extends androidx.recyclerview.widget.RecyclerView$ViewHolder { *; }
-keep class * extends androidx.recyclerview.widget.RecyclerView$Adapter { *; }

# Keep security, cryptography, and model classes
-keep class com.my.seekapplication.VaultSecurityManager { *; }
-keep class com.my.seekapplication.IntruderCamera { *; }
-keep class com.my.seekapplication.VaultAdapter$* { *; }

# Keep Kotlin Coroutines
-dontwarn kotlinx.coroutines.**
-keep class kotlinx.coroutines.** { *; }

# Line numbers & attributes for readable stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
