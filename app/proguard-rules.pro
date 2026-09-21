# Retrofit / OkHttp / kotlinx-serialization
# 属性：Retrofit 全靠反射读它们——少一个都会在运行期才炸
# （生成方法体、参数注解、以及 suspend 方法的泛型签名）
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault, *Annotation*
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
# Retrofit 接口按「类」保下来（官方规则：R8 full mode 下它看不到 Proxy 产生的子类）。
# 注意：这只解决「接口本身」的问题；响应类型被删是另一回事，见下面胖乖那段。
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface <1>
-keep,allowobfuscation,allowshrinking class retrofit2.Response
# suspend 函数的 Continuation 同样要保泛型签名
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# Tink（androidx.security:security-crypto 的传递依赖，EncryptedSharedPreferences 用）：
# 它引用了 errorprone 的编译期注解类，运行期不需要——R8 找不到会直接失败，声明 don't warn 即可。
-dontwarn com.google.errorprone.annotations.**

# Tink 里我们只用 EncryptedSharedPreferences（AEAD/HMAC，经 TinkConfig.register() 静态注册），
# 它自带的 KeysDownloader（远程密钥集下载，我们不用）引用了这些缺失的可选依赖——
# 只声明 don't warn，不做 keep（keep 会把整个 KeysDownloader 连同缺口一起保住）。
-dontwarn com.google.api.client.**
-dontwarn org.joda.time.**

# ── 胖乖（Qiekj）响应模型：只被「泛型签名 + 运行期反射」引用，必须整包保 ──────────
# R8 静态分析看不到使用者，会把它们当死代码整类删掉（2026-09-21 实测：usage.txt 里
# `EmptyData`、`EmptyData$Companion` 整类移除、`EmptyDataSerializer.INSTANCE` 字段移除），
# 于是 `ApiEnvelope<EmptyData>` 的签名实参退化成 Object，
# 症状 = 调用时抛「Unable to create converter for ApiEnvelope<java.lang.Object> for method …」
# ——而且**只有用到被删类型的接口会炸**（平衡/设备那些类型恰好被别处引用而幸存），
# 极易误判成序列化或签名问题，所以这里不按类逐个保，整包保掉、一劳永逸。
#
# 定位手法（下次遇到同类问题照做）：release 构建临时加 `-printusage <临时路径>`，
# 在报告里找**没有冒号的行** = 被整类删除的类；再看报错里
# 「Unable to create converter for X<Y>」的实参 Y 是不是就在其中。
-keep class edu.jxslu.schedule.data.qiekj.** { *; }

# ── kotlinx-serialization 的反射式查找（防御性保留，2026-09-21 加）─────────────
# Retrofit 这条链路是**运行期反射**：converter 用方法返回类型的 KType 去查
# `@Serializable` 类的 `Companion.serializer(...)`（DataStore 那条链是编译期拿 serializer，不受影响）。
# 规则与 kotlinx-serialization-core 自带的 META-INF/proguard 同形——JAR 依赖的 consumer 规则
# 未必被 AGP 收集，这里显式写一份；两份规则幂等，多写不亏、少了会炸在运行期。
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-dontnote kotlinx.serialization.**
