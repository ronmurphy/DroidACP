# R8 shrinks and optimises the release APK. The libraries we use (OkHttp,
# kotlinx.serialization, coroutines, Compose) ship their own keep rules; these are
# just belt-and-braces for optional dependencies OkHttp probes for at runtime.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.slf4j.**

# Keep line numbers so crash stack traces are readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
