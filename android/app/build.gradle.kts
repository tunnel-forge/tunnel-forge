import org.gradle.api.GradleException
import org.gradle.api.tasks.Exec
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("kotlin-android")
    id("dev.flutter.flutter-gradle-plugin")
}

val keystorePath = System.getenv("TUNNEL_FORGE_ANDROID_KEYSTORE_PATH")
val keystorePassword = System.getenv("TUNNEL_FORGE_ANDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("TUNNEL_FORGE_ANDROID_KEY_ALIAS")
val releaseKeyPassword = System.getenv("TUNNEL_FORGE_ANDROID_KEY_PASSWORD")
val releaseSigningInputs =
    mapOf(
        "TUNNEL_FORGE_ANDROID_KEYSTORE_PATH" to keystorePath,
        "TUNNEL_FORGE_ANDROID_KEYSTORE_PASSWORD" to keystorePassword,
        "TUNNEL_FORGE_ANDROID_KEY_ALIAS" to releaseKeyAlias,
        "TUNNEL_FORGE_ANDROID_KEY_PASSWORD" to releaseKeyPassword,
    )
val hasReleaseSigning =
    releaseSigningInputs.values.all { !it.isNullOrBlank() }

if (!hasReleaseSigning && releaseSigningInputs.values.any { !it.isNullOrBlank() }) {
    throw GradleException(
        "Incomplete Android release signing configuration. Set all of: " +
            releaseSigningInputs.keys.joinToString(", "),
    )
}

val localProperties =
    Properties().apply {
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.isFile) {
            localPropertiesFile.inputStream().use(::load)
        }
    }
val androidSdkPath =
    providers.environmentVariable("ANDROID_HOME").orNull
        ?: providers.environmentVariable("ANDROID_SDK_ROOT").orNull
        ?: localProperties.getProperty("sdk.dir")

// Android NDK prebuilts are split by host OS/CPU. Keep this resolver here so
// native lint/format tasks work on Linux, macOS, and Windows contributors' machines.
fun hostNdkPrebuiltDir(): String {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    return when {
        os.contains("windows") -> "windows-x86_64"
        os.contains("mac") && (arch == "aarch64" || arch == "arm64") -> "darwin-aarch64"
        os.contains("mac") -> "darwin-x86_64"
        else -> "linux-x86_64"
    }
}

// Prefer the newest installed NDK tool when PATH does not provide one. This
// keeps clang-tidy aligned with the Android compiler/sysroot used by CMake.
fun findAndroidNdkTool(name: String): File? {
    val ndkRoot = androidSdkPath?.let { file(it).resolve("ndk") } ?: return null
    val executableName = if (System.getProperty("os.name").lowercase().contains("windows")) "$name.exe" else name
    val prebuiltDir = hostNdkPrebuiltDir()
    return ndkRoot
        .listFiles()
        ?.sortedByDescending { it.name }
        ?.map { it.resolve("toolchains/llvm/prebuilt/$prebuiltDir/bin/$executableName") }
        ?.firstOrNull { it.isFile }
}

fun androidNdkClang(name: String): String =
    findAndroidNdkTool(name)?.absolutePath ?: name

// Check PATH explicitly so Gradle passes an absolute executable path to Exec.
// Environment variables below still allow CI to pin an exact LLVM version.
fun findPathTool(name: String): String? {
    val executableName = if (System.getProperty("os.name").lowercase().contains("windows")) "$name.exe" else name
    return System.getenv("PATH")
        ?.split(File.pathSeparator)
        ?.map { File(it, executableName) }
        ?.firstOrNull { it.isFile && it.canExecute() }
        ?.absolutePath
}

// Resolution order:
// 1. CLANG_FORMAT / CLANG_TIDY for pinned CI or local overrides.
// 2. PATH for standard developer installs.
// 3. Android NDK fallback so Android-only setups still work.
val clangFormatCommand =
    providers.environmentVariable("CLANG_FORMAT").orNull
        ?: findPathTool("clang-format")
        ?: findAndroidNdkTool("clang-format")?.absolutePath
        ?: "clang-format"
val clangTidyCommand =
    providers.environmentVariable("CLANG_TIDY").orNull
        ?: findPathTool("clang-tidy")
        ?: findAndroidNdkTool("clang-tidy")?.absolutePath
        ?: "clang-tidy"
val gradleWrapperCommand =
    if (System.getProperty("os.name").lowercase().contains("windows")) {
        "gradlew.bat"
    } else {
        "./gradlew"
    }
val androidGoJniLibsDir = layout.buildDirectory.dir("androidGoJniLibs")

android {
    val enableClangTidy = providers.gradleProperty("tunnelForgeClangTidy").orNull
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
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_EXPORT_COMPILE_COMMANDS=ON",
                )
                if (enableClangTidy != null) {
                    arguments += listOf("-DTUNNEL_FORGE_CLANG_TIDY=$enableClangTidy")
                    arguments += listOf("-DCLANG_TIDY_COMMAND=$clangTidyCommand")
                }
                if (innerUdpNoChecksum != null) {
                    arguments += listOf("-DTUNNEL_FORGE_ESP_INNER_UDP_NO_CHECKSUM=$innerUdpNoChecksum")
                }
                if (keymatVariant != null) {
                    arguments += listOf("-DTUNNEL_FORGE_KEYMAT_VARIANT=$keymatVariant")
                }
            }
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
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

    packaging {
        resources {
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir(androidGoJniLibsDir)
        }
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.8.2")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("io.netty:netty-codec-http:4.2.12.Final")
    implementation("io.netty:netty-codec-socks:4.2.12.Final")
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

val androidNativeCSources =
    fileTree("src/main/cpp") {
        include("**/*.c", "**/*.h")
    }
val repoRootDir = rootProject.projectDir.parentFile
val nativeTestBuildDir = repoRootDir.resolve("build/native_test")
val androidGoModuleDir = rootProject.projectDir.resolve("gvisor")

data class AndroidGoAbi(
    val abi: String,
    val goArch: String,
    val clangName: String,
    val goArm: String? = null,
)

val androidGoAbis =
    listOf(
        AndroidGoAbi("arm64-v8a", "arm64", "aarch64-linux-android31-clang"),
        AndroidGoAbi("armeabi-v7a", "arm", "armv7a-linux-androideabi31-clang", goArm = "7"),
        AndroidGoAbi("x86_64", "amd64", "x86_64-linux-android31-clang"),
        AndroidGoAbi("x86", "386", "i686-linux-android31-clang"),
    )

tasks.register("buildAndroidGoNative") {
    group = "build"
    description = "Builds the Android Go native shared library for Android ABIs."
    inputs.files(androidGoModuleDir.resolve("go.mod"), androidGoModuleDir.resolve("go.sum"), androidGoModuleDir.resolve("main.go"))
    outputs.dirs(androidGoAbis.map { androidGoJniLibsDir.get().asFile.resolve(it.abi) })
    doLast {
        if (!androidGoModuleDir.resolve("go.mod").isFile) {
            throw GradleException("Missing android/gvisor/go.mod. Run the documented Go module setup before building.")
        }
        val goBuildCache = layout.buildDirectory.dir("go-cache").get().asFile
        goBuildCache.mkdirs()
        androidGoAbis.forEach { abi ->
            val outDir = androidGoJniLibsDir.get().asFile.resolve(abi.abi)
            outDir.mkdirs()
            exec {
                workingDir = androidGoModuleDir
                executable = "go"
                args(
                    "build",
                    "-buildmode=c-shared",
                    "-trimpath",
                    "-o",
                    outDir.resolve("libtunnel_gvisor.so").absolutePath,
                    ".",
                )
                environment("CGO_ENABLED", "1")
                environment("GOOS", "android")
                environment("GOARCH", abi.goArch)
                abi.goArm?.let { environment("GOARM", it) }
                environment("CC", androidNdkClang(abi.clangName))
                environment("GOCACHE", goBuildCache.absolutePath)
            }
        }
    }
}

tasks.matching {
    it.name == "preBuild" ||
        it.name.startsWith("externalNativeBuild") ||
        (it.name.startsWith("merge") && it.name.endsWith("JniLibFolders"))
}.configureEach {
    dependsOn("buildAndroidGoNative")
}

// Rewrites Android native C sources in-place using the repo .clang-format file.
tasks.register<Exec>("formatAndroidNativeC") {
    group = "formatting"
    description = "Formats Android native C sources with clang-format."
    commandLine(clangFormatCommand, "-i")
    args(androidNativeCSources.files.sortedBy { it.path })
}

// CI-friendly formatting check: fails if clang-format would change any C source.
tasks.register<Exec>("checkAndroidNativeCFormat") {
    group = "verification"
    description = "Checks Android native C source formatting with clang-format."
    commandLine(clangFormatCommand, "--dry-run", "--Werror")
    args(androidNativeCSources.files.sortedBy { it.path })
}

// Runs the native debug build with clang-tidy enabled through CMake, so analysis
// uses the same NDK includes, defines, and target flags as the Android build.
tasks.register<Exec>("lintAndroidNativeC") {
    group = "verification"
    description = "Builds Android native C sources with clang-tidy enabled."
    workingDir = rootProject.projectDir
    commandLine(gradleWrapperCommand, ":app:externalNativeBuildDebug", "-PtunnelForgeClangTidy=ON")
}

tasks.register<Exec>("configureNativeCTest") {
    group = "verification"
    description = "Configures host-side native C unit tests with CMake."
    commandLine("cmake", "-S", repoRootDir.resolve("test/native"), "-B", nativeTestBuildDir)
}

tasks.register<Exec>("buildNativeCTest") {
    group = "verification"
    description = "Builds host-side native C unit tests."
    dependsOn("configureNativeCTest")
    commandLine("cmake", "--build", nativeTestBuildDir)
}

tasks.register<Exec>("testNativeC") {
    group = "verification"
    description = "Runs host-side native C unit tests."
    dependsOn("buildNativeCTest")
    commandLine("ctest", "--test-dir", nativeTestBuildDir, "--output-on-failure")
}

tasks.register("checkNativeC") {
    group = "verification"
    description = "Runs native C format, lint, and unit-test checks."
    dependsOn("checkAndroidNativeCFormat", "lintAndroidNativeC", "testNativeC")
}
