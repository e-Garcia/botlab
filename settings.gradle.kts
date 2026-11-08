pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // freeRASP (Talsec) - unscoped; community edition requires auth on GCP Artifact Registry
        maven("https://europe-west3-maven.pkg.dev/talsec-artifact-repository/freerasp")
        maven("https://jitpack.io") {
            content {
                includeGroup("com.github.fingerprintjs")
                includeGroup("com.github.instacart")
            }
        }
    }
}

rootProject.name = "botlab"
