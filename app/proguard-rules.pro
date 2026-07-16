-keep class ai.onnxruntime.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class dev.sk2andy.voiceisolation.service.PrivilegedIsolationUserService {
    public <init>(android.content.Context);
}
