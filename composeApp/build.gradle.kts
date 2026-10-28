import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.math.sqrt

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.application")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

listOf(
    "src/commonMain/kotlin/cz/taximares/humorradio",
    "src/androidMain/kotlin/cz/taximares/humorradio",
    "src/iosMain/kotlin/cz/taximares/humorradio"
).forEach { relPath ->
    val dir = project.file(relPath)
    if (dir.exists()) {
        dir.deleteRecursively()
    }
}

kotlin {
    androidTarget {
        compilations.all {
            kotlinOptions {
                jvmTarget = "17"
            }
        }
    }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
            }
        }
        val androidMain by getting {
            dependencies {
                implementation("androidx.activity:activity-compose:1.9.2")
                implementation("androidx.media3:media3-exoplayer:1.3.1")
                implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
                implementation("androidx.media3:media3-datasource:1.3.1")
                implementation("androidx.media3:media3-common:1.3.1")
            }
        }
        val iosMain by creating {
            dependsOn(commonMain)
        }
        val iosX64Main by getting { dependsOn(iosMain) }
        val iosArm64Main by getting { dependsOn(iosMain) }
        val iosSimulatorArm64Main by getting { dependsOn(iosMain) }
    }
}

val rawBuildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
    ?: System.getenv("PROJECT_BUILD_NUMBER")?.toIntOrNull()
    ?: 1
val ciBuildNumber = if (rawBuildNumber < 100) rawBuildNumber + 100 else rawBuildNumber

android {
    namespace = "cz.taximares.radiopm"
    compileSdk = 36

    defaultConfig {
        applicationId = "cz.taximares.radiopm"
        minSdk = 24
        targetSdk = 36
        versionCode = ciBuildNumber
        versionName = "1.0.$ciBuildNumber"
    }

    signingConfigs {
        create("release") {
            val ksPath = System.getenv("CM_KEYSTORE_PATH")
            if (!ksPath.isNullOrBlank() && file(ksPath).exists()) {
                storeFile = file(ksPath)
                storePassword = System.getenv("CM_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CM_KEY_ALIAS")
                keyPassword = System.getenv("CM_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            val releaseSigning = signingConfigs.getByName("release")
            signingConfig = if (releaseSigning.storeFile != null) {
                releaseSigning
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

val generateIosIcon = tasks.register("generateIosIcon") {
    doLast {
        val iconDir = rootProject.file("iosApp/iosApp/Assets.xcassets/AppIcon.appiconset")
        iconDir.mkdirs()
        val outFile = File(iconDir, "app-icon-1024.png")
        val width = 1024
        val height = 1024

        val rawData = ByteArrayOutputStream(height * (width * 3 + 1))
        for (y in 0 until height) {
            rawData.write(0)
            for (x in 0 until width) {
                var r = 12
                var g = 28
                var b = 44

                val inBody = x in 170..854 && y in 310..830
                val inScreen = x in 230..794 && y in 365..475
                val dx1 = (x - 370).toDouble()
                val dy1 = (y - 660).toDouble()
                val dist1 = sqrt(dx1 * dx1 + dy1 * dy1)
                val dx2 = (x - 680).toDouble()
                val dy2 = (y - 640).toDouble()
                val dist2 = sqrt(dx2 * dx2 + dy2 * dy2)

                if (inBody) {
                    r = 255; g = 213; b = 79
                }
                if (inScreen || dist1 < 135.0 || dist2 < 85.0) {
                    r = 14; g = 24; b = 34
                }
                if (dist1 in 45.0..75.0 || dist2 in 25.0..45.0) {
                    r = 77; g = 208; b = 225
                }

                rawData.write(r)
                rawData.write(g)
                rawData.write(b)
            }
        }

        val compressedData = ByteArrayOutputStream()
        DeflaterOutputStream(compressedData, Deflater(Deflater.BEST_SPEED)).use {
            it.write(rawData.toByteArray())
        }

        fun writeChunk(out: java.io.OutputStream, type: String, data: ByteArray) {
            val lenBytes = byteArrayOf(
                (data.size ushr 24).toByte(),
                (data.size ushr 16).toByte(),
                (data.size ushr 8).toByte(),
                data.size.toByte()
            )
            out.write(lenBytes)
            val typeBytes = type.toByteArray(Charsets.US_ASCII)
            out.write(typeBytes)
            out.write(data)
            val crc = CRC32()
            crc.update(typeBytes)
            crc.update(data)
            val v = crc.value.toInt()
            out.write(
                byteArrayOf(
                    (v ushr 24).toByte(),
                    (v ushr 16).toByte(),
                    (v ushr 8).toByte(),
                    v.toByte()
                )
            )
        }

        FileOutputStream(outFile).use { fos ->
            fos.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
            val ihdr = byteArrayOf(
                0, 0, 4, 0,
                0, 0, 4, 0,
                8,
                2,
                0, 0, 0
            )
            writeChunk(fos, "IHDR", ihdr)
            writeChunk(fos, "IDAT", compressedData.toByteArray())
            writeChunk(fos, "IEND", ByteArray(0))
        }

        val contentsJson = File(iconDir, "Contents.json")
        contentsJson.writeText(
            """
            {
              "images" : [
                {
                  "filename" : "app-icon-1024.png",
                  "idiom" : "universal",
                  "platform" : "ios",
                  "size" : "1024x1024"
                }
              ],
              "info" : {
                "author" : "xcode",
                "version" : 1
              }
            }
            """.trimIndent()
        )
    }
}

tasks.matching { it.name.startsWith("link") && it.name.contains("FrameworkIos") }.configureEach {
    dependsOn(generateIosIcon)
}
