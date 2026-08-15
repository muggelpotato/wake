@file:Suppress("VulnerableLibrariesLocal")
import xyz.jpenilla.runpaper.task.RunServer

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

val claimed = mapOf(
    "1.21" to "1.21-R0.1-SNAPSHOT",
    "1.21.1" to "1.21.1-R0.1-SNAPSHOT",
    "1.21.3" to "1.21.3-R0.1-SNAPSHOT",
    "1.21.4" to "1.21.4-R0.1-SNAPSHOT",
    "1.21.5" to "1.21.5-R0.1-SNAPSHOT",
    "1.21.6" to "1.21.6-R0.1-SNAPSHOT",
    "1.21.7" to "1.21.7-R0.1-SNAPSHOT",
    "1.21.8" to "1.21.8-R0.1-SNAPSHOT",
    "1.21.9" to "1.21.9-R0.1-SNAPSHOT",
    "1.21.10" to "1.21.10-R0.1-SNAPSHOT",
    "1.21.11" to "1.21.11-R0.1-SNAPSHOT",
    "26.1.1" to "26.1.1.build.29-alpha",
    "26.1.2" to "26.1.2.build.74-stable",
    "26.2" to "26.2.build.111-stable",
)

val matrixFiles = mapOf(
    "eula.txt" to "eula=true",
    "server.properties" to """
        server-port=25565
        enable-rcon=true
        rcon.password=wake-dev
        rcon.port=25585
        difficulty=peaceful
        gamemode=creative
        level-type=flat
        spawn-protection=0
    """,
    "spigot.yml" to """
        settings:
          moved-wrongly-threshold: 1000
          moved-too-quickly-multiplier: 1000
    """,
).mapValues { (_, content) -> content.trimIndent() + "\n" }

val compileClaimed = claimed.map { (version, coordinate) ->
    val release = if (version.startsWith("26.")) 25 else 21
    @Suppress("UnstableApiUsage")
    val pinned = configurations.resolvable("paperApi$version") {
        extendsFrom(configurations.compileOnly.get(), configurations.implementation.get())
        attributes {
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_API))
            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
            attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
            attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, release)
        }
        resolutionStrategy.eachDependency {
            if (requested.name == "paper-api") useVersion(coordinate)
        }
    }
    val home = file("testenv/matrix/$version")
    tasks.register<RunServer>("run$version") {
        group = "wake"
        description = "Runs a Paper $version server with this build's jar"
        minecraftVersion(version)
        runDirectory(home)
        javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(release) }
        jvmArgs("-Xms1G", "-Xmx4G")
        pluginJars.from(tasks.shadowJar)
        val seed = matrixFiles
        doFirst {
            home.mkdirs()
            seed.forEach { (name, content) -> File(home, name).takeIf { !it.exists() }?.writeText(content) }
        }
    }
    tasks.register<JavaCompile>("compileAgainst$version") {
        group = "wake"
        description = "Compiles the source against paper-api $coordinate"
        source(sourceSets.main.get().java)
        classpath = files(pinned)
        destinationDirectory = layout.buildDirectory.dir("classes/claimed/$version")
        javaCompiler = javaToolchains.compilerFor { languageVersion = java.toolchain.languageVersion }
        options.release = release
    }
}

tasks.register("checkVersions") {
    group = "wake"
    description = "Compiles the source against every Paper version Wake claims"
    dependsOn(compileClaimed)
}

tasks.runServer {
    minecraftVersion("1.21.11")
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
    jvmArgs("-Xms2G", "-Xmx6G")
    pluginJars.from(tasks.shadowJar)
}

tasks {
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
        archiveClassifier = ""
        relocate("com.github.retrooper.packetevents", "dev.muggel.wake.libs.packetevents.api")
        relocate("io.github.retrooper.packetevents", "dev.muggel.wake.libs.packetevents.impl")
        relocate("co.aikar.idb", "dev.muggel.wake.libs.idb")
    }

    assemble {
        dependsOn(shadowJar)
    }
}