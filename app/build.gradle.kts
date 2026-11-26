plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// CI sets GITHUB_RUN_NUMBER, so every GitHub Actions build gets a higher versionCode
// and Android lets you install it over the previous one.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

// Release builds are signed with a private key kept outside the repo. Set these four
// Gradle properties, e.g. in ~/.gradle/gradle.properties (CI sets them from GitHub
// secrets). Without them, the release APK comes out unsigned.
val releaseSigning: Map<String, String>? = listOf("STORE_FILE", "STORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")
    .associateWith { (findProperty("DROIDACP_RELEASE_$it") as String?)?.takeIf { v -> v.isNotBlank() } }
    .takeIf { props -> props.values.all { it != null } }
    ?.mapValues { it.value!! }

android {
    namespace = "com.droidacp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.droidacp"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
    }

    signingConfigs {
        // A public development key, committed to the repo, for debug builds only.
        // Anyone can sign with it, so the releases you install use the private key below.
        create("dev") {
            storeFile = rootProject.file("keystore/droidacp-dev.jks")
            storePassword = "droidacp"
            keyAlias = "droidacp"
            keyPassword = "droidacp"
        }
        if (releaseSigning != null) {
            create("release") {
                storeFile = file(releaseSigning.getValue("STORE_FILE"))
                storePassword = releaseSigning.getValue("STORE_PASSWORD")
                keyAlias = releaseSigning.getValue("KEY_ALIAS")
                keyPassword = releaseSigning.getValue("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (releaseSigning != null) signingConfigs.getByName("release") else null
        }
        debug {
            signingConfig = signingConfigs.getByName("dev")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // TabDesk's agent service (IAgentService.aidl, copied unchanged from TabDesk).
        aidl = true
        buildConfig = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // Licence texts that several libraries (Jackson, used by the Anthropic SDK)
            // ship under the same file name, which the APK packager refuses to merge.
            excludes += "/META-INF/{LICENSE,LICENSE.md,LICENSE.txt,NOTICE,NOTICE.md,NOTICE.txt,DEPENDENCIES,INDEX.LIST}"
            excludes += "/META-INF/FastDoubleParser-{LICENSE,NOTICE}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
