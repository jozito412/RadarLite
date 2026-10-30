plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.jozito.radarlite"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.jozito.radarlite"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }

    base {
        archivesName.set("RadarLite")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Location & Lifecycle Service & SavedState
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.savedstate)
    implementation(libs.play.services.location)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

val rootDirectory = rootProject.layout.projectDirectory
val debugApkDirectory = layout.buildDirectory.dir("outputs/apk/debug")

tasks.register("copyRadarLiteApk") {
    doNotTrackState("Direct copy of APK installer")
    val targetDir = rootDirectory
    val sourceDir = debugApkDirectory
    doLast {
        val apkDir = sourceDir.get().asFile
        val sourceApk = apkDir.listFiles { _, name -> name.endsWith(".apk") }?.firstOrNull()
        if (sourceApk != null) {
            val destFile = targetDir.file("RadarLite.apk").asFile
            sourceApk.copyTo(destFile, overwrite = true)
            println("RadarLite APK copiado com sucesso para: ${destFile.absolutePath}")
        }
    }
}

tasks.matching { it.name == "assembleDebug" }.configureEach {
    finalizedBy("copyRadarLiteApk")
}