import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

val appVersionName = providers.gradleProperty("imab.versionName").get()
val appVersionCode = providers.gradleProperty("imab.versionCode").get().toInt()

/** Writes BuildInfo.kt so the app can show its version. */
val generateBuildInfo by tasks.registering {
    val out = layout.buildDirectory.dir("generated/buildinfo")
    val name = appVersionName
    val code = appVersionCode
    inputs.property("versionName", name)
    inputs.property("versionCode", code)
    outputs.dir(out)
    doLast {
        val file = out.get().file("com/z/imab/BuildInfo.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            "package com.z.imab\n\n/** Generated from gradle.properties. */\nobject BuildInfo {\n" +
                "    const val VERSION_NAME = \"$name\"\n    const val VERSION_CODE = $code\n}\n"
        )
    }
}

/** Keeps the iPhone app's version (iosApp/Version.xcconfig) the same as gradle.properties. */
val syncIosVersion by tasks.registering {
    val file = rootProject.file("iosApp/Version.xcconfig")
    val name = appVersionName
    val code = appVersionCode
    inputs.property("versionName", name)
    inputs.property("versionCode", code)
    outputs.file(file)
    doLast {
        val text = file.readText()
            .replace(Regex("IMAB_VERSION_NAME = .*"), "IMAB_VERSION_NAME = $name")
            .replace(Regex("IMAB_VERSION_CODE = .*"), "IMAB_VERSION_CODE = $code")
        file.writeText(text)
    }
}
generateBuildInfo.configure { dependsOn(syncIosVersion) }

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm("desktop")
    // iPhone: a static framework the Xcode project in iosApp/ links (see iosApp/README.md).
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    compilerOptions {
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
        optIn.add("androidx.compose.foundation.layout.ExperimentalLayoutApi")
    }

    sourceSets {
        val desktopMain by getting
        val desktopTest by getting
        // Code shared by Android and desktop, which both run on the JVM (javax.crypto).
        val jvmSharedMain by creating { dependsOn(commonMain.get()) }
        androidMain.get().dependsOn(jvmSharedMain)
        desktopMain.dependsOn(jvmSharedMain)
        // iPhone and the iPhone simulator share all their code.
        val iosMain by creating { dependsOn(commonMain.get()) }
        val iosTest by creating { dependsOn(commonTest.get()) }
        listOf("iosArm64", "iosSimulatorArm64").forEach { t ->
            getByName("${t}Main").dependsOn(iosMain)
            getByName("${t}Test").dependsOn(iosTest)
        }

        commonMain.configure { kotlin.srcDir(generateBuildInfo) }
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(libs.compose.icons.core)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.kotlinx.coroutines.android)
        }
        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
        }
        desktopTest.dependencies {
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
        }
    }
}

/**
 * Android release signing. On GitHub the key comes from secrets (as environment variables); locally
 * from keystore.properties (not in git) pointing at the key file. Without either, release builds
 * are unsigned.
 */
val releaseSigning: Map<String, String>? = run {
    System.getenv("IMAB_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }?.let { file ->
        return@run mapOf(
            "storeFile" to file,
            "storePassword" to System.getenv("IMAB_KEYSTORE_PASSWORD").orEmpty(),
            "keyAlias" to System.getenv("IMAB_KEY_ALIAS").orEmpty(),
            "keyPassword" to System.getenv("IMAB_KEY_PASSWORD").orEmpty(),
        )
    }
    val local = rootProject.file("keystore.properties")
    if (!local.isFile) return@run null
    val props = Properties().apply { local.inputStream().use { load(it) } }
    props.stringPropertyNames().associateWith { props.getProperty(it) }
}

android {
    namespace = "com.z.imab"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.z.imab"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
    lint {
        // AGP 8's lint reads Kotlin metadata up to 2.2, so it can't analyze this Kotlin 2.4 code and
        // only prints errors. Revisit when moving to AGP 9.
        checkReleaseBuilds = false
    }
    signingConfigs {
        releaseSigning?.let { s ->
            create("release") {
                storeFile = file(s.getValue("storeFile"))
                storePassword = s.getValue("storePassword")
                keyAlias = s.getValue("keyAlias")
                keyPassword = s.getValue("keyPassword")
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.z.imab.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi)
            packageName = "IMAB"
            packageVersion = appVersionName
            description = "I Made A Budget"
            vendor = "Z"
            modules("java.naming", "jdk.unsupported")
            windows {
                iconFile.set(project.file("icons/IMAB.ico"))
                menuGroup = "IMAB"
                shortcut = true
                perUserInstall = true
                dirChooser = true
                // Keep this the same forever so new versions upgrade the installed one.
                upgradeUuid = "e00e6839-c06e-4e15-94c1-b6cf76da11c3"
            }
            macOS {
                bundleID = "com.z.imab"
                iconFile.set(project.file("icons/IMAB.icns"))
                appCategory = "public.app-category.finance"
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    if (project.hasProperty("screenshots")) systemProperty("imab.screenshots", "1")
    if (project.hasProperty("icons")) systemProperty("imab.icons", "1")
}
