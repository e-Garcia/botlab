pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // freeRASP (Talsec) and its device-fingerprinting dependency, scoped so nothing else can resolve from them.
        maven("https://europe-west3-maven.pkg.dev/talsec-artifact-repository/freerasp") {
            content { includeGroup("com.aheaditec.tasec") }
        }
        maven("https://jitpack.io") {
            content {
                includeGroup("com.github.fingerprintjs")
                includeGroup("com.github.instacart")
            }
        }
    }
}

rootProject.name = "botlab"
