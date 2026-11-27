import java.io.FileInputStream
import java.util.Properties

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        load(FileInputStream(localPropertiesFile))
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val appVersionName = providers.gradleProperty("appVersionName").getOrElse("3.0")

/**
 * Čas sestavení (s) pro versionCode. Přes ValueSource: s configuration cache se jinak čas spočítal jen při
 * konfiguraci a další sestavení z cache dostala STARÝ versionCode (nižší než už nainstalovaný = APK nejde
 * nainstalovat jako aktualizace). Hodnotu ValueSource Gradle při každém sestavení načte znovu, a když se liší,
 * konfiguraci z cache nepoužije.
 */
abstract class BuildTimeSource : ValueSource<Long, ValueSourceParameters.None> {
    override fun obtain(): Long = System.currentTimeMillis() / 1000
}
val buildTimeSec: Long = providers.of(BuildTimeSource::class.java) {}.get()

android {
    namespace = "com.vasik96.spojeuk"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.vasik96.spojeuk"
        minSdk = 33
        targetSdk = 37
        versionCode = buildTimeSec.toInt()
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // jen ARM: x86 / x86_64 (~27 MB knihovny MapLibre) jsou pro emulátory, ne pro telefony
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        manifestPlaceholders["MAPS_API_KEY"] = localProperties.getProperty("MAPS_API_KEY") ?: ""
        buildConfigField(
            "String",
            "CARTO_API_KEY",
            "\"${localProperties.getProperty("CARTO_API_KEY") ?: ""}\""
        )
        buildConfigField(
            "String",
            "MAPYCZ_API_KEY",
            "\"${localProperties.getProperty("MAPYCZ_API_KEY") ?: ""}\""
        )
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("local-key.jks")
            storePassword = localProperties.getProperty("KEYSTORE_PASSWORD") ?: ""
            keyAlias = localProperties.getProperty("KEY_ALIAS") ?: "my-alias"
            keyPassword = localProperties.getProperty("KEY_PASSWORD") ?: ""
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
    //implementation("org.osmdroid:osmdroid-android:6.1.20") // openstreetmaps
    //google maps:
    implementation("com.google.maps.android:maps-compose:9.0.1")
    implementation("com.google.android.gms:play-services-maps3d:16.0.1")
    implementation("com.google.android.gms:play-services-location:21.4.0")

    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3:1.5.0-rc01")

    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation("org.maplibre.gl:android-sdk:13.7.1")

    implementation("org.apache.commons:commons-compress:1.28.0")
}
