@file:Suppress("VulnerableLibrariesLocal")
plugins {
    id("java-library")
    id("xyz.jpenilla.run-paper") version "3.0.2"
    id("com.gradleup.shadow") version "8.3.10"
    id("wake-testenv")
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.codemc.io/repository/maven-releases/")
    maven("https://repo.aikar.co/content/groups/aikar/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    implementation("com.github.retrooper:packetevents-spigot:2.13.0")

    compileOnly("com.zaxxer:HikariCP:5.1.0")
    compileOnly("org.xerial:sqlite-jdbc:3.46.0.0")
    compileOnly("org.mariadb.jdbc:mariadb-java-client:3.4.0")
    compileOnly("io.lettuce:lettuce-core:6.8.2.RELEASE")
    implementation("co.aikar:idb-core:1.0.0-SNAPSHOT")
    implementation("co.aikar:idb-bukkit:1.0.0-SNAPSHOT")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
}

val java21 = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }

tasks {
    runServer {
        minecraftVersion("1.21.11")
        javaLauncher = java21
        jvmArgs("-Xms2G", "-Xmx6G")
        pluginJars.from(shadowJar)
    }

    processResources {
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }

    jar {
        enabled = false
    }

    shadowJar {
        archiveClassifier.set("")
        relocate("com.github.retrooper.packetevents", "dev.muggel.wake.libs.packetevents.api")
        relocate("io.github.retrooper.packetevents", "dev.muggel.wake.libs.packetevents.impl")
        relocate("co.aikar.idb", "dev.muggel.wake.libs.idb")
    }

    assemble {
        dependsOn(shadowJar)
    }
}