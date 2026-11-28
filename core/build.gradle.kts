// Pure Kotlin/JVM library: no Android imports allowed in here, so it can be unit
// tested on any machine with `./gradlew :core:test`.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    // Anthropic's official SDK, used only by agent/AnthropicModelClient.kt.
    implementation("com.anthropic:anthropic-java:3.13.0")
    // The SDK reads JSON with Kotlin reflection; keep that library on our Kotlin version.
    implementation(kotlin("reflect"))

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    // A real WebSocket server for WebSocketTransportTest (pings, a slow listener).
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
