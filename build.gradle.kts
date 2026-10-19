// Bot Lab: a test app that looks at how it is being operated the way anti-abuse SDKs do, so we can see what our
// harness (or any automation) reveals. It embeds freeRASP, a real RASP SDK, and adds its own behaviour analysis.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.egarcia.botlab"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.egarcia.botlab"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/INDEX.LIST")
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.talsec.freerasp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
