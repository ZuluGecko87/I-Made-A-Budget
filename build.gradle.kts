// Build tools (the Android, Kotlin and Compose Gradle plugins) bring in libraries with known
// vulnerabilities at the versions they ask for. These raise them to fixed releases in the same
// major version. They only affect the build, not the app. Re-check with OSV when upgrading plugins.
buildscript {
    val netty = "4.1.139.Final"
    val bouncyCastle = "1.86"
    val fixed = listOf(
        "com.google.guava:guava:33.7.2-jre",
        "com.google.protobuf:protobuf-java:3.25.9",
        "commons-io:commons-io:2.22.0",
        "org.apache.commons:commons-compress:1.28.0",
        "org.bitbucket.b_c:jose4j:0.9.7",
        "org.jdom:jdom2:2.0.6.1",
        "org.bouncycastle:bcprov-jdk18on:$bouncyCastle",
        "org.bouncycastle:bcpkix-jdk18on:$bouncyCastle",
        "org.bouncycastle:bcutil-jdk18on:$bouncyCastle",
    ) + listOf(
        "buffer", "codec", "codec-http", "codec-http2", "codec-socks", "common", "handler",
        "handler-proxy", "resolver", "transport", "transport-native-unix-common",
    ).map { "io.netty:netty-$it:$netty" }
    dependencies {
        constraints {
            fixed.forEach { add("classpath", it) { because("fixes known vulnerabilities (OSV / GitHub advisories)") } }
        }
    }
}

plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinSerialization) apply false
}
