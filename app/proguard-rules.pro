# DQMP Native TV Proguard Rules
-keep class com.dqmp.native.display.data.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses
-dontwarn okio.**
-dontwarn javax.annotation.**
