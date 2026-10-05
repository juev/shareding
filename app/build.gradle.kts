plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
}

val releaseKeystore = providers.environmentVariable("SHAREDING_RELEASE_KEYSTORE").orNull
val releasePassword = providers.environmentVariable("SHAREDING_RELEASE_PASSWORD").orNull
require((releaseKeystore == null && releasePassword == null) ||
    (!releaseKeystore.isNullOrBlank() && !releasePassword.isNullOrBlank())) {
    "Set both SHAREDING_RELEASE_KEYSTORE and SHAREDING_RELEASE_PASSWORD to sign a release"
}

val testTlsRoot = layout.buildDirectory.dir("generated/test-tls")
val testTlsKeystore = testTlsRoot.map { it.file("androidTest/assets/test_tls.p12") }
val testTlsCertificate = testTlsRoot.map { it.file("debug/res/raw/test_tls_cert.pem") }
val keytool = file(System.getProperty("java.home")).resolve("bin/keytool" +
    if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "")
val generateTestTlsKey = tasks.register<Exec>("generateTestTlsKey") {
    val keystore = testTlsKeystore.get().asFile
    inputs.property("format", "legacy-pkcs12")
    inputs.property("validityDays", 3650)
    outputs.file(keystore)
    doFirst {
        keystore.parentFile.mkdirs()
        keystore.delete()
    }
    commandLine(keytool, "-J-Dkeystore.pkcs12.legacy", "-genkeypair", "-noprompt",
        "-storetype", "PKCS12", "-keystore", keystore, "-storepass", "test-password",
        "-keypass", "test-password", "-alias", "tls", "-keyalg", "RSA", "-keysize", "2048",
        "-validity", "3650", "-dname", "CN=localhost", "-ext",
        "SAN=dns:localhost,dns:linkding.invalid,dns:origin.invalid,ip:127.0.0.1")
}
val generateTestTls = tasks.register<Exec>("generateTestTls") {
    dependsOn(generateTestTlsKey)
    val certificate = testTlsCertificate.get().asFile
    inputs.file(testTlsKeystore)
    outputs.file(certificate)
    doFirst { certificate.parentFile.mkdirs() }
    commandLine(keytool, "-exportcert", "-rfc", "-storetype", "PKCS12",
        "-keystore", testTlsKeystore.get().asFile, "-storepass", "test-password", "-alias", "tls",
        "-file", certificate)
}

tasks.matching {
    it.name in setOf("generateDebugResources", "mapDebugSourceSetPaths",
        "processDebugNavigationResources", "mergeDebugResources", "mergeDebugAndroidTestAssets")
}.configureEach { dependsOn(generateTestTls) }

android {
    namespace = "org.evsyukov.shareding"
    compileSdk = 36

    sourceSets.getByName("debug").res.srcDir(testTlsRoot.map { it.dir("debug/res") })
    sourceSets.getByName("androidTest").assets.srcDir(testTlsRoot.map { it.dir("androidTest/assets") })

    defaultConfig {
        applicationId = "org.evsyukov.shareding"
        minSdk = 29
        targetSdk = 36
        versionCode = 25
        versionName = "0.3.0-rc.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = releasePassword
                keyAlias = "shareding"
                keyPassword = releasePassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        jniLibs {
            // graphics-path ships stripped binaries; skip AGP's redundant strip attempt.
            keepDebugSymbols.add("**/libandroidx.graphics.path.so")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.04.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    kapt("androidx.room:room-compiler:2.8.5")

    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.23.2")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.room:room-testing:2.8.5")
    androidTestImplementation("androidx.work:work-testing:2.12.0")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
