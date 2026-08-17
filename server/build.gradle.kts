plugins {
    application
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.openchat.server.MainKt")
}

dependencies {
    implementation(project(":domain"))
    implementation("io.ktor:ktor-server-cio:3.0.3")
    implementation("io.ktor:ktor-server-content-negotiation:3.0.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.3")
    implementation("org.xerial:sqlite-jdbc:3.47.2.0")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.15")
}
