import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("kotlin-android")
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    val innerUdpNoChecksum = providers.gradleProperty("tunnelForgeInnerUdpNoChecksum").orNull
    val keymatVariant = providers.gradleProperty("tunnelForgeKeymatVariant").orNull
    namespace = "io.github.evokelektrique.tunnelforge"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "io.github.evokelektrique.tunnelforge"
        minSdk = 31
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
                if (innerUdpNoChecksum != null) {
                    arguments += listOf("-DTUNNEL_FORGE_ESP_INNER_UDP_NO_CHECKSUM=$innerUdpNoChecksum")
                }
                if (keymatVariant != null) {
                    arguments += listOf("-DTUNNEL_FORGE_KEYMAT_VARIANT=$keymatVariant")
                }
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            // One-shot KEYMAT log after Quick Mode (sensitive). Release builds omit this flag.
            externalNativeBuild {
                cmake {
                    arguments += listOf("-DTUNNEL_FORGE_DEBUG_ESP_KEYMAT=1")
                }
            }
            // A/B inner UDP checksum:
            //   ./gradlew assembleDebug -PtunnelForgeInnerUdpNoChecksum=1  (default behavior, checksum=0)
            //   ./gradlew assembleDebug -PtunnelForgeInnerUdpNoChecksum=0  (compute inner UDP checksum)
            // KEYMAT variant selection:
            //   ./gradlew assembleDebug -PtunnelForgeKeymatVariant=1|2|3
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.8.2")
    implementation("androidx.core:core-ktx:1.15.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.2.0")
}

flutter {
    source = "../.."
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
