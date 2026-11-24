# R8 shrinks and optimises the release APK. The libraries we use (OkHttp,
# kotlinx.serialization, coroutines, Compose) ship their own keep rules; these are
# just belt-and-braces for optional dependencies OkHttp probes for at runtime.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.slf4j.**

# The Anthropic SDK (used by the built-in agent) and Jackson, which it uses for JSON,
# ship their own keep rules. These silence warnings about desktop-Java classes that
# Jackson mentions but never needs on Android.
-dontwarn java.beans.**
-dontwarn org.w3c.dom.bootstrap.**
-dontwarn javax.xml.stream.**
-dontwarn javax.annotation.**

# Keep line numbers so crash stack traces are readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
