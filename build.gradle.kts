plugins {
    id("java")
}

group = "com.github.phantazm"
version = "0.0.1"
description = "A simple backup plugin which uses regex matching and can back up an entire Minecraft server."

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.18.2-R0.1-SNAPSHOT")
}