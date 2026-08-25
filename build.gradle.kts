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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    implementation("ch.qos.logback:logback-classic:1.5.12")

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("socar.mcp.protocol.MainKt")
}

tasks.shadowJar {
    archiveBaseName.set("protocol-codegen-mcp")
    archiveClassifier.set("all")
    archiveVersion.set("")
    mergeServiceFiles()
}

tasks.test {
    useJUnitPlatform()
}
