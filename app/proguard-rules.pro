# biweekly reads its properties relative to its own package at class initialization, so R8 must not rename it.
-keep class biweekly.Biweekly

# Parameter values construct extensions and discover predefined constants through reflection.
-keepclassmembers,allowobfuscation class biweekly.parameter.** extends biweekly.parameter.EnumParameterValue {
    <init>(java.lang.String);
    <init>(java.lang.String, biweekly.ICalVersion[]);
}
-keepclassmembers,allowobfuscation class biweekly.parameter.** {
    public static <fields>;
}
-keepclassmembers,allowobfuscation class biweekly.ICalDataType {
    public static <fields>;
}
-dontwarn org.jsoup.**

# Biweekly's optional jCal JSON adapter is not used by the ICS-only importer/exporter.
-dontwarn com.fasterxml.jackson.annotation.JsonFormat
-dontwarn com.fasterxml.jackson.databind.DeserializationContext
-dontwarn com.fasterxml.jackson.databind.JsonDeserializer
-dontwarn com.fasterxml.jackson.databind.JsonSerializer
-dontwarn com.fasterxml.jackson.databind.SerializerProvider
-dontwarn com.fasterxml.jackson.databind.module.SimpleModule
-dontwarn com.fasterxml.jackson.databind.ser.std.StdSerializer
