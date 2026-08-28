# Sideload release currently ships without minify. These keeps are here
# so enabling R8 later does not strip Compose / serialization.

-keepattributes *Annotation*, InnerClasses, Signature
-keepclassmembers class ** {
    @kotlinx.serialization.Required <fields>;
}
-keep,includedescriptorclasses class com.folio.launcher.**$$serializer { *; }
-keepclassmembers class com.folio.launcher.** {
    *** Companion;
}
-dontwarn org.slf4j.**
