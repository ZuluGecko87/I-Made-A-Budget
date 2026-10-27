import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm("desktop")

    compilerOptions {
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
        optIn.add("androidx.compose.foundation.layout.ExperimentalLayoutApi")
    }

    sourceSets {
        val desktopMain by getting
        val desktopTest by getting

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
    buildTypes {
        getByName("release") { isMinifyEnabled = false }
    }
}

compose.desktop {
    application {
        mainClass = "com.z.imab.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "IMAB"
            packageVersion = appVersionName
            description = "I Made A Budget"
            vendor = "Z"
            modules("java.naming", "jdk.unsupported")
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
