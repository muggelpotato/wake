import org.gradle.api.GradleException
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import java.io.File

val forwardingSecret = "wake-dev-secret"
val composePath: String = file("testenv/docker-compose.yml").absolutePath
val wakeConfigFile = file("run/plugins/wake/config.yml")
val paperGlobalFile = file("run/config/paper-global.yml")
val serverPropsFile = file("run/server.properties")
val runPluginsDir = file("run/plugins")
val paper2PluginsDir = file("testenv/paper2/plugins")
val forwardingSecretFile = file("testenv/velocity/forwarding.secret")
val shadowJarFile = tasks.named("shadowJar", AbstractArchiveTask::class).flatMap { it.archiveFile }

fun composeRunner(composePath: String): (Array<out String>, (String) -> Unit) -> Unit = { args, log ->
    val docker = listOf("docker", "C:/Program Files/Docker/Docker/resources/bin/docker.exe").firstOrNull { candidate ->
        try {
            val p = ProcessBuilder(candidate, "--version").redirectErrorStream(true).start()
            p.inputStream.readAllBytes()
            p.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    } ?: throw GradleException("Docker not found: it is required for the MariaDB test environment (testenv/)")
    val daemonReachable = try {
        val probe = ProcessBuilder(docker, "info", "--format", "{{.ServerVersion}}").redirectErrorStream(true).start()
        probe.inputStream.readAllBytes()
        probe.waitFor() == 0
    } catch (_: Exception) {
        false
    }
    if (!daemonReachable) {
        throw GradleException(
            "Docker is installed but its engine isn't running. Start Docker Desktop (or run " + "`docker desktop start`) and rerun. The MariaDB test environment (testenv/) needs a live daemon."
        )
    }
    val builder = ProcessBuilder(listOf(docker, "compose", "-f", composePath) + args).redirectErrorStream(true)
    val binDirs = listOfNotNull(File(docker).parent, "C:\\Program Files\\Docker\\Docker\\resources\\bin".takeIf { File(it).isDirectory }).distinct()
    if (binDirs.isNotEmpty()) {
        val env = builder.environment()
        val pathKey = env.keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: "PATH"
        env[pathKey] = binDirs.joinToString(File.pathSeparator) + File.pathSeparator + (env[pathKey] ?: "")
    }
    val p = builder.start()
    p.inputStream.bufferedReader().forEachLine { log("  $it") }
    if (p.waitFor() != 0) {
        throw GradleException("docker compose ${args.joinToString(" ")} failed")
    }
}

val testEnvUp = tasks.register("testEnvUp") {
    group = "wake"
    description = "Sync the MariaDB/Velocity docker test environment with run/plugins/wake/config.yml"
    dependsOn("shadowJar")
    val compose = composeRunner(composePath)
    val secret = forwardingSecret
    val secretFile = forwardingSecretFile
    val wakeConfig = wakeConfigFile
    val paperGlobal = paperGlobalFile
    val serverProps = serverPropsFile
    val runPlugins = runPluginsDir
    val paper2Plugins = paper2PluginsDir
    val jarSrc = shadowJarFile
    doLast {
        val log = logger
        val mariadb = wakeConfig.exists() &&
                Regex("""(?m)^\s*type:\s*["']?mariadb""").containsMatchIn(wakeConfig.readText())
        if (paperGlobal.exists()) {
            val text = paperGlobal.readText()
            val block = Regex("""velocity:\r?\n(\s+)enabled: .+\r?\n\s+online-mode: .+\r?\n\s+secret: .*""")
            if (!block.containsMatchIn(text)) {
                log.warn("testenv: proxies.velocity block not found in paper-global.yml. Set enabled: $mariadb and secret: '$secret' manually")
            } else {
                val patched = block.replace(text) { m ->
                    val indent = m.groupValues[1]
                    "velocity:\n${indent}enabled: $mariadb\n${indent}online-mode: true\n${indent}secret: '$secret'"
                }
                if (patched != text) {
                    paperGlobal.writeText(patched)
                    log.lifecycle("testenv: primary Velocity forwarding ${if (mariadb) "enabled" else "disabled"}")
                }
            }
        } else if (mariadb) {
            log.lifecycle("testenv: run/config/paper-global.yml missing: boot the server once, then rerun runServer to enable proxy joins on the primary")
        }
        if (serverProps.exists()) {
            val wanted = if (mariadb) "false" else "true"
            val text = serverProps.readText()
            val patched = text.replace(Regex("(?m)^online-mode=.*$"), "online-mode=$wanted")
            if (patched != text) {
                serverProps.writeText(patched)
                val why = if (mariadb) "required behind Velocity" else "no proxy in sqlite mode"
                log.lifecycle("testenv: set online-mode=$wanted on the primary ($why)")
            }
        }
        if (!mariadb) {
            return@doLast
        }
        paper2Plugins.deleteRecursively()
        if (runPlugins.isDirectory) {
            runPlugins.walkTopDown().onEnter { it.name != ".paper-remapped" }.forEach { src ->
                val dest = File(paper2Plugins, src.relativeTo(runPlugins).path)
                if (src.isDirectory) {
                    dest.mkdirs()
                } else {
                    dest.parentFile?.mkdirs()
                    src.copyTo(dest, overwrite = true)
                }
            }
        }
        jarSrc.get().asFile.copyTo(File(paper2Plugins, "wake.jar"), overwrite = true)
        val paper2WakeConfig = File(paper2Plugins, "wake/config.yml")
        if (paper2WakeConfig.exists()) {
            var text = paper2WakeConfig.readText()
            text = Regex("""(?ms)^(database:.*?^\s+host:\s*).*?$""").replace(text) { m -> m.groupValues[1] + "\"mariadb\"" }
            text = Regex("""(?ms)^(sync:.*?^\s+host:\s*).*?$""").replace(text) { m -> m.groupValues[1] + "\"valkey\"" }
            paper2WakeConfig.writeText(text)
        }
        secretFile.parentFile?.mkdirs()
        secretFile.writeText(secret)
        compose(arrayOf("up", "-d", "--wait", "--quiet-pull", "mariadb", "valkey")) { log.lifecycle(it) }
        compose(arrayOf("up", "-d", "--force-recreate", "--quiet-pull", "paper2", "velocity")) { log.lifecycle(it) }
    }
}

tasks.named("runServer") {
    dependsOn(testEnvUp)
    finalizedBy("testEnvDown")
}

tasks.register("testEnvDown") {
    group = "wake"
    description = "Stop the MariaDB/Velocity docker test environment"
    val compose = composeRunner(composePath)
    doLast {
        val log = logger
        try {
            compose(arrayOf("down")) { log.lifecycle(it) }
        } catch (e: Exception) {
            val nothingToTearDown = e.message?.let {
                it.startsWith("Docker not found") || it.startsWith("Docker is installed but")
            } ?: false
            if (!nothingToTearDown) {
                log.lifecycle("testenv: teardown skipped (${e.message})")
            }
        }
    }
}
