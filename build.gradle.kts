plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    application
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation("io.modelcontextprotocol:kotlin-sdk:0.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("io.ktor:ktor-server-netty:3.1.3")
    implementation("ch.qos.logback:logback-classic:1.5.12")
}

application {
    mainClass.set("socar.mcp.calendar.MainKt")
}

tasks.shadowJar {
    archiveBaseName.set("google-calendar-mcp")
    archiveClassifier.set("all")
    archiveVersion.set("")
    mergeServiceFiles()
}
