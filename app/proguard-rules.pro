# R8 is on for release. kotlinx.serialization ships its own consumer rules, and every
# decode here uses the compile-time serializer (decodeFromString<T>), so nothing is found
# by reflection. These keeps are belt and braces for the generated serializers.

-keepattributes *Annotation*, InnerClasses, Signature
-keepclassmembers class ** {
    @kotlinx.serialization.Required <fields>;
}
-keep,includedescriptorclasses class com.folio.launcher.**$$serializer { *; }
-keepclassmembers class com.folio.launcher.** {
    *** Companion;
}
-dontwarn org.slf4j.**
