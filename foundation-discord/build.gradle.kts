plugins {
    id("foundation.base-conventions")
    id("foundation.publishing-conventions")
    id("com.github.johnrengelman.shadow")
}

dependencies {
    api(projects.foundationCommon)
    api(libs.discord4j.core)
    runtimeOnly(kotlin("stdlib"))
    runtimeOnly(libs.slf4j.simple)
}

tasks.shadowJar {
    archiveFileName.set("FoundationServer.jar")
    manifest {
        attributes(
            "Main-Class" to "com.xpdustry.foundation.discord.FoundationDiscordKt",
            "Implementation-Title" to "FoundationServer",
            "Implementation-Version" to project.version,
            "Implementation-Vendor" to "Xpdustry",
        )
    }
    doFirst {
        val file = temporaryDir.resolve("VERSION.txt")
        file.writeText(project.version.toString())
        from(file)
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.register("getArtifactPath") {
    doLast { println(tasks.shadowJar.get().archiveFile.get().toString()) }
}

tasks.register<JavaExec>("runFoundationDiscord") {
    workingDir = temporaryDir
    dependsOn(tasks.shadowJar)
    classpath(tasks.shadowJar)
    description = "Starts a local Foundation discord bot"
}
