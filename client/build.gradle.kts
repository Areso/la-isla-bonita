plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

val ktorVersion = project.property("ktorVersion") as String

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("islabonita.client.MainKt")
}

dependencies {
    implementation(project(":shared"))
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("org.slf4j:slf4j-nop:2.0.16")
}

// Let `gradle :client:run` read from the terminal.
tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
