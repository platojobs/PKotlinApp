# 当前工程使用 R8 全量压缩与资源收缩，以下规则覆盖网络层、序列化与 Kotlin 反射。

# ---------- 通用 ----------
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault

# ---------- Retrofit / OkHttp ----------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn retrofit2.**
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# Retrofit 的接口方法依赖运行时注解与泛型签名
-keep interface com.platojobs.pkotlinapp.data.remote.ApiService { *; }

# ---------- Moshi（KotlinJsonAdapterFactory 反射模式） ----------
-keep class com.platojobs.pkotlinapp.data.remote.** { *; }
-keepclasseswithmembers class * {
    @com.squareup.moshi.* <methods>;
}
-keep @com.squareup.moshi.JsonQualifier interface *
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}

# ---------- kotlin-reflect ----------
-keep class kotlin.Metadata { *; }
-keep class kotlin.reflect.** { *; }
-keep class kotlin.jvm.internal.** { *; }
-dontwarn kotlin.reflect.**

# ---------- Room 实体（保留列名与泛型） ----------
-keep class com.platojobs.pkotlinapp.data.local.** { *; }
