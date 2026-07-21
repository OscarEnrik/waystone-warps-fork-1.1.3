import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.0"
    id("com.gradleup.shadow") version "9.3.1"
    kotlin("plugin.serialization") version "2.3.0"
    // Only added to get compile-time access to net.minecraft.* internals for
    // FixedAnvilInventoryImpl (see dev.mizarc.waystonewarps.compat.anvil). If IF ships an
    // upstream fix for its 26.1+ anvil GUI bug, this plugin and the dev bundle dependency below
    // can be removed again.
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21"
}

group = providers.gradleProperty("projectGroup").get()
version = providers.gradleProperty("projectVersion").get()

val localPropertiesProvider = providers.fileContents(layout.projectDirectory.file("local.properties"))
    .asText
    .map { content ->
        Properties().apply { load(content.reader()) }
    }

fun getProperty(key: String): String {
    return localPropertiesProvider
        .map { it.getProperty(key) }
        .orElse(providers.gradleProperty(key))
        .getOrElse("")
}

repositories {
    mavenLocal()
    mavenCentral()
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
    maven {
        url = uri("https://repo.aikar.co/content/groups/aikar/")
    }
    maven {
        url = uri("https://jitpack.io")
    }
    maven {
        name = 'glaremasters repo'
        url = 'https://repo.glaremasters.me/repository/towny/'
    }
    maven {
        name = "Multiverse"
        url = uri("https://repo.onarandombox.com/public")
    }
}

dependencies {
    testImplementation(kotlin("test"))
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    paperweight.paperDevBundle("26.2.build.+")
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:2.3.0")
    compileOnly("io.insert-koin:koin-core-jvm:4.1.1")
    implementation("co.aikar:idb-core:1.0.0-SNAPSHOT")
    implementation("co.aikar:acf-paper:0.5.1-SNAPSHOT")
    compileOnly("com.zaxxer:HikariCP:7.0.2")
    implementation("com.github.stefvanschie.inventoryframework:IF:0.12.0")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1")
    compileOnly("me.xdrop:fuzzywuzzy:1.4.0")
    // compileOnly("com.github.TownyAdvanced:towny:0.103.1.0")
    compileOnly ("com.palmergames.bukkit.towny:towny:0.103.1.0")
    compileOnly("org.mvplugins.multiverse.inventories:multiverse-inventories:5.3.5")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveClassifier = null
    relocate("com.github.stefvanschie.inventoryframework", "dev.mizarc.waystonewarps.inventoryframework")

    manifest {
        attributes("paperweight-mappings-namespace" to "mojang")
    }
}

tasks.processResources {
    val realName = rootProject.name
    val realVersion = project.version.toString()

    // Ensure Gradle re-runs this task if the version changes
    inputs.property("name", realName)
    inputs.property("version", realVersion)

    filesMatching("plugin.yml") {
        filter { line ->
            when {
                line.trim().startsWith("name:") -> "name: $realName"
                line.trim().startsWith("version:") -> "version: $realVersion"
                else -> line
            }
        }
    }
}

tasks.register<Copy>("deploy") {
    dependsOn(tasks.shadowJar)
    from(layout.buildDirectory.dir("libs"))
    into(getProperty("plugin.server.path"))
    rename { fileName -> "${rootProject.name}-${version}.jar" }
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    doFirst {
        logger.lifecycle("Target deployment path: ${getProperty("plugin.server.path")}")
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

paperweight {
    javaLauncher = javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(25)
    }
}
