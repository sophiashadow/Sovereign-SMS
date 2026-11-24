# EventBus
-keepattributes *Annotation*
-keepclassmembers class ** {
    @org.greenrobot.eventbus.Subscribe <methods>;
}
-keep enum org.greenrobot.eventbus.ThreadMode { *; }

# Keep `Companion` object fields of serializable classes.
# This avoids serializer lookup through `getDeclaredClasses` as done for named companion objects.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

# Keep `serializer()` on companion objects (both default and named) of serializable classes.
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep `INSTANCE.serializer()` of serializable objects.
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# Gson
-keep class org.fossify.commons.models.SimpleContact { *; }
-keep class org.fossify.messages.models.Attachment { *; }
-keep class org.fossify.messages.models.MessageAttachment { *; }

# ez-vcard's parameter registries use reflection to enumerate public constants
# and create runtime values for unknown TYPE/MEDIATYPE parameters.
-keepclassmembers,allowobfuscation class ezvcard.parameter.* extends ezvcard.parameter.VCardParameter {
    public static final <fields>;
    <init>(java.lang.String);
}

-keepclassmembers,allowobfuscation class ezvcard.parameter.* extends ezvcard.parameter.MediaTypeParameter {
    <init>(java.lang.String, java.lang.String, java.lang.String);
}

# Sovereign SMS: SQLCipher is reached from JNI and by Room's open helper; keep it whole
# (the AAR's consumer rules already keep net.zetetic.database.**; this is belt and braces).
-keep class net.zetetic.** { *; }
-dontwarn net.zetetic.**

# Sovereign SMS: MMS sending/receiving goes through the bundled klinker-derived classes and
# platform reflection; keep upstream's MMS packages and the Telephony receivers intact.
-keep class com.android.mms.** { *; }
-keep class com.klinker.android.send_message.** { *; }
-keep class com.google.android.mms.** { *; }
-dontwarn com.android.mms.**
-dontwarn com.google.android.mms.**

# Sovereign SMS: strip debug/verbose/info logging (ours and libraries') from release builds so
# message metadata cannot reach logcat that way. Warnings and errors are kept for crash triage.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
