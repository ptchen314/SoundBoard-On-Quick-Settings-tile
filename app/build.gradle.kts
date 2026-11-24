import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ---- 自動滾版本號 ----
val versionFile = rootProject.file("version.properties")
val versionProps = Properties().apply { versionFile.inputStream().use { load(it) } }
val versionMajor = versionProps.getProperty("major", "1").toInt()
val versionMinor = versionProps.getProperty("minor", "0").toInt()
var buildNumber = versionProps.getProperty("build", "0").toInt()

// 只有真的要打包時才加 1（Android Studio 同步、lint、test 不會動到版本號）
val isPackaging = gradle.startParameter.taskNames.any { task ->
    val name = task.substringAfterLast(':')
    listOf("assemble", "bundle", "install", "package").any { name.startsWith(it, ignoreCase = true) }
}
if (isPackaging) {
    buildNumber += 1
    // 直接改寫數字那一行，保留檔案的註解和排版
    versionFile.writeText(
        versionFile.readText().replace(Regex("(?m)^build=.*$"), "build=$buildNumber")
    )
}
val appVersionName = "$versionMajor.$versionMinor.${"%02d".format(buildNumber)}"
logger.lifecycle("SoundBoardTile version $appVersionName (code ${buildNumber.coerceAtLeast(1)})")

android {
    namespace = "com.soundboard.qstile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.soundboard.qstile"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber.coerceAtLeast(1)
        versionName = appVersionName
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        // NewPipeExtractor 在 minSdk < 33 需要 core library desugaring
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    // APK 檔名帶版本號，例如 SoundBoardTile-1.0.01-debug.apk
    applicationVariants.all {
        val variant = this
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "SoundBoardTile-${variant.versionName}-${variant.buildType.name}.apk"
        }
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // YouTube 音訊下載
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.27.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.4")
}
