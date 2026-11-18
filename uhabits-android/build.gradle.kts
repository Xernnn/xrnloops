/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */

import java.util.Properties

plugins {
    alias(libs.plugins.agp)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ktlint.plugin)
    alias(libs.plugins.mokkery)
}

// xrnloops: an optional signing key shared with xrnrule (both apps must carry the same signature), kept
// outside both repos in ../xrn-signing.properties (see XRNLOOPS.md). Without that file,
// builds are signed with this machine's debug key.
val xrnSigning: Properties? = rootProject.file("../xrn-signing.properties").takeIf { it.isFile }?.let { f ->
    Properties().apply { f.inputStream().use { load(it) } }
}

tasks.compileLint {
    dependsOn("updateTranslators")
}

/*
Added on top of kotlinOptions to work around this issue:
https://youtrack.jetbrains.com/issue/KTIJ-24311/task-current-target-is-17-and-kaptGenerateStubsProductionDebugKotlin-task-current-target-is-1.8-jvm-target-compatibility-should#focus=Comments-27-6798448.0-0
Updating gradle might fix this, so try again in the future to remove this and run:
./gradlew --rerun-tasks :uhabits-android:kaptGenerateStubsReleaseKotlin
If this doesn't produce any warning, try to remove it.
 */
kotlin {
    jvmToolchain(17)
}

android {
    namespace = "org.isoron.uhabits"
    compileSdk = 36

    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets", "../uhabits-core/assets/main")
        }
    }

    defaultConfig {
        versionCode = 20305
        versionName = "2.3.5"
        minSdk = 28
        targetSdk = 36
        // xrnloops: its own app id so it installs next to the store Loop (XRNLOOPS.md).
        applicationId = "com.xern.xrnloops"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (xrnSigning != null) {
            create("xrn") {
                storeFile = rootProject.file("..").resolve(xrnSigning.getProperty("storeFile"))
                storePassword = xrnSigning.getProperty("storePassword")
                keyAlias = xrnSigning.getProperty("keyAlias")
                keyPassword = xrnSigning.getProperty("keyPassword")
            }
        }
        if (System.getenv("LOOP_KEY_ALIAS") != null) {
            create("release") {
                keyAlias = System.getenv("LOOP_KEY_ALIAS")
                keyPassword = System.getenv("LOOP_KEY_PASSWORD")
                storeFile = file(System.getenv("LOOP_KEY_STORE"))
                storePassword = System.getenv("LOOP_STORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.txt")
            if (signingConfigs.findByName("release") != null) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                signingConfigs.findByName("xrn")?.let { signingConfig = it }
            }
        }

        debug {
            enableUnitTestCoverage = true
            signingConfigs.findByName("xrn")?.let { signingConfig = it }
        }
    }

    // NotoSans and this copy of FontAwesome are for uhabits-core's JVM canvas (tests), in the
    // folder this app takes its migrations from. On Android the canvas draws with MiSans.
    androidResources {
        ignoreAssetsPattern = "!.git:!*~:!NotoSans-Regular.ttf:!NotoSans-Bold.ttf:!FontAwesome.ttf"
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        targetCompatibility(JavaVersion.VERSION_17)
        sourceCompatibility(JavaVersion.VERSION_17)
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures.viewBinding = true
    lint.abortOnError = false
}

mokkery {
    defaultMockMode.set(dev.mokkery.MockMode.autofill)
    stubs.allowClassInheritance.set(true)
    stubs.allowConcreteClassInstantiation.set(true)
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.appIntro)
    implementation(libs.jsr305)
    implementation(libs.kotlin.inject.runtime)
    implementation(libs.kotlin.stdlib.jdk8)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.appcompat)
    implementation(libs.legacy.preference.v14)
    implementation(libs.legacy.support.v4)
    implementation(libs.material)
    implementation(libs.documentfile)
    implementation(libs.konfetti.xml)
    implementation(project(":uhabits-core"))
    ksp(libs.kotlin.inject.compiler)

    androidTestImplementation(libs.annotation)
    androidTestImplementation(libs.kotlin.inject.runtime)
    androidTestImplementation(libs.espresso.contrib)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.rules)
    androidTestImplementation(libs.uiautomator)

    testImplementation(libs.kotlin.inject.runtime)
    testImplementation(libs.junit.junit)
    // xrnloops: upstream's unit tests use kotlinx.coroutines.test but never declared it (XRNLOOPS.md).
    testImplementation(libs.kotlinx.coroutines.test)
    // xrnloops: the real org.json, for the sync's records; android.jar's only throws in a unit test.
    testImplementation(libs.json)
}
