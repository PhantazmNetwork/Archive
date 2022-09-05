plugins {
    java
}

group = "com.github.phantazmnetwork"
version = "0.2.0"
description = "A simple backup plugin which uses regex matching and can back up an entire Minecraft server."

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

// Include the license in any compiled binary
tasks.withType<Jar> {
    from(".") {
        include("LICENSE")
    }
}

tasks.processResources {
    inputs.property("version", project.version)

    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.19.2-R0.1-SNAPSHOT")
}