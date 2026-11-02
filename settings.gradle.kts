plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "la-isla-bonita"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include("shared", "server", "client")
