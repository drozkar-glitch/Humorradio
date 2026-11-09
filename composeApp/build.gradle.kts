import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

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
        getByName("debug") {
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null) {
                signingConfig = releaseSigning
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
        val canvas = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g2d = canvas.createGraphics()
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2d.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)

        val darkBg = Color(0x0C, 0x1C, 0x2C)
        val gold = Color(0xFF, 0xD5, 0x4F)
        val cyan = Color(0x4D, 0xD0, 0xE1)

        // Pozadi shodné s ic_launcher_background.xml (#0C1C2C)
        g2d.color = darkBg
        g2d.fillRect(0, 0, width, height)

        // Prevod souřadnic z Android adaptive ikony (vyrez 16..92 z 108x108 viewportu do 1024x1024)
        val viewMin = 16.0
        val viewSpan = 76.0
        fun mapPos(v: Double): Double = ((v - viewMin) / viewSpan) * 1024.0
        fun mapLen(d: Double): Double = (d / viewSpan) * 1024.0

        // 1. Sikma antena: M34,38 L68,24 (strokeWidth 3.5, round cap)
        g2d.color = gold
        g2d.stroke = BasicStroke(mapLen(3.5).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g2d.draw(Line2D.Double(mapPos(34.0), mapPos(38.0), mapPos(68.0), mapPos(24.0)))

        // 2. Kulicka na konci anteny: stred (68, 24), polomer 3
        val antR = mapLen(3.0)
        g2d.fill(Ellipse2D.Double(mapPos(68.0) - antR, mapPos(24.0) - antR, antR * 2.0, antR * 2.0))

        // 3. Telo radia: x=24..84, y=38..78, polomer rohu 4
        val bodyCorner = mapLen(8.0)
        g2d.color = gold
        g2d.fill(
            RoundRectangle2D.Double(
                mapPos(24.0),
                mapPos(38.0),
                mapLen(60.0),
                mapLen(40.0),
                bodyCorner,
                bodyCorner
            )
        )

        // 4. Tmavý displej ladění: x=30..78, y=43..51
        val screenCorner = mapLen(2.5)
        g2d.color = darkBg
        g2d.fill(
            RoundRectangle2D.Double(
                mapPos(30.0),
                mapPos(43.0),
                mapLen(48.0),
                mapLen(8.0),
                screenCorner,
                screenCorner
            )
        )

        // 5. Tyrkysová stupnice uvnitř displeje: M34,47 L74,47 (strokeWidth 2)
        g2d.color = cyan
        g2d.stroke = BasicStroke(mapLen(2.0).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g2d.draw(Line2D.Double(mapPos(34.0), mapPos(47.0), mapPos(74.0), mapPos(47.0)))

        // 6. Levy reproduktor: vnejsi kruh stred (42, 65), r=11 (#0C1C2C), vnitrni r=5 (#FFD54F)
        val leftOuterR = mapLen(11.0)
        g2d.color = darkBg
        g2d.fill(
            Ellipse2D.Double(
                mapPos(42.0) - leftOuterR,
                mapPos(65.0) - leftOuterR,
                leftOuterR * 2.0,
                leftOuterR * 2.0
            )
        )
        val leftInnerR = mapLen(5.0)
        g2d.color = gold
        g2d.fill(
            Ellipse2D.Double(
                mapPos(42.0) - leftInnerR,
                mapPos(65.0) - leftInnerR,
                leftInnerR * 2.0,
                leftInnerR * 2.0
            )
        )

        // 7. Pravy reproduktor / knoflik: vnejsi kruh stred (68, 65), r=8 (#0C1C2C), vnitrni r=4 (#4DD0E1)
        val rightOuterR = mapLen(8.0)
        g2d.color = darkBg
        g2d.fill(
            Ellipse2D.Double(
                mapPos(68.0) - rightOuterR,
                mapPos(65.0) - rightOuterR,
                rightOuterR * 2.0,
                rightOuterR * 2.0
            )
        )
        val rightInnerR = mapLen(4.0)
        g2d.color = cyan
        g2d.fill(
            Ellipse2D.Double(
                mapPos(68.0) - rightInnerR,
                mapPos(65.0) - rightInnerR,
                rightInnerR * 2.0,
                rightInnerR * 2.0
            )
        )

        g2d.dispose()

        val rawData = ByteArrayOutputStream(height * (width * 3 + 1))
        for (y in 0 until height) {
            rawData.write(0)
            for (x in 0 until width) {
                val rgb = canvas.getRGB(x, y)
                rawData.write((rgb shr 16) and 0xFF)
                rawData.write((rgb shr 8) and 0xFF)
                rawData.write(rgb and 0xFF)
            }
        }

        val compressedData = ByteArrayOutputStream()
        DeflaterOutputStream(compressedData, Deflater(Deflater.BEST_COMPRESSION)).use {
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
