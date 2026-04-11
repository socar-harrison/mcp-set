plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
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
    implementation("io.modelcontextprotocol:kotlin-sdk:0.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-io-core:0.6.0")
    implementation("io.ktor:ktor-server-netty:3.0.3")
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
