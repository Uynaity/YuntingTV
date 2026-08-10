# ============================================================
# R8 / ProGuard 规则
# 目标：release 开启代码与资源压缩的同时，保证以下反射/序列化场景不被误删。
# ============================================================

# ---------- kotlinx.serialization ----------
# 保留 @Serializable 类自动生成的 $serializer，避免 R8 full mode 误删。
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

# kotlinx.serialization 运行时
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---------- Retrofit ----------
# Retrofit 自带 consumer 规则，这里仅补充接口签名与泛型信息。
-keepattributes Signature, Exceptions
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response

# ---------- OkHttp ----------
-dontwarn okhttp3.**
-dontwarn okio.**

# ---------- Media3 / ExoPlayer ----------
# Media3 自带 consumer 规则，通常无需额外配置；保险起见忽略告警。
-dontwarn androidx.media3.**

# ---------- Coil ----------
-dontwarn coil.**

# ---------- Kotlin 协程 ----------
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
