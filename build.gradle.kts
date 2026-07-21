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
        url = uri("https://oss.sonatype.org/content/repositories/snapshots")
    }
    maven {
        url = uri("https://oss.sonatype.org/content/repositories/central")
    }
    maven {
        url = uri("https://repo.aikar.co/content/groups/aikar/")
    }
    maven {
        url = uri("https://jitpack.io")
    }
    maven {
        url = uri("https://repo.glaremasters.me/repository/towny/")
    }
    maven {
        name = "Multiverse"
        url = uri("https://repo.onarandombox.com/content/groups/public/")
    }
}

dependencies {
    testImplementation(kotlin("test"))
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    // Replaces the old compileOnly("io.papermc.paper:paper-api:...") - the dev bundle already
    // includes the full Paper API, plus Mojang-mapped NMS access needed by
    // dev.mizarc.waystonewarps.compat.anvil.FixedAnvilInventoryImpl. Pinned to 26.1.2 to match
    // the actual deployed server (Purpur 26.1.2-2587-dc4a255) - adjust if you're on a different
    // build; see comment at bottom of file for how to check.
    paperweight.paperDevBundle("26.2.build.+")
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:2.3.0")
    compileOnly("io.insert-koin:koin-core-jvm:4.1.1")
    implementation("co.aikar:idb-core:1.0.0-SNAPSHOT")
    implementation("co.aikar:acf-paper:0.5.1-SNAPSHOT")
    compileOnly("com.zaxxer:HikariCP:7.0.2")
    implementation("com.github.stefvanschie.inventoryframework:IF:0.12.0")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1")
    compileOnly("me.xdrop:fuzzywuzzy:1.4.0")
    compileOnly("com.palmergames.bukkit.towny:towny:0.102.0.14")
    // compileOnly("org.mvplugins.multiverse.inventories:multiverse-inventories:5.3.5")
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

    // IF ships version-specific NMS accessor code. Per IF's own setup docs, this must be
    // relocated into our own package, and (since we're a Paper plugin) the jar manifest must
    // declare that our classes are Mojang-mapped so Paper doesn't try to remap them again on
    // load - remapping already-Mojang-mapped NMS-touching bytecode is what corrupts IF's
    // Anvil GUI accessor and produces the IllegalAccessError on Slot.slot.
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
    // paperweight-userdev's own setup tasks (applyPaperclipPatch, etc.) run under whatever JVM
    // Gradle uses by default, which is this project's JDK 21 toolchain. Minecraft 26.x's own
    // build/patch tooling needs a newer JDK than that, so we point *just* paperweight's internal
    // work at a newer one - this doesn't change what bytecode version our own plugin compiles to
    // (still JDK 21, set above).
    javaLauncher = javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

// --- paperweight-userdev notes ---
// 1. "26.1.2.build.+" resolves the latest published dev bundle build for MC 26.1.2, matching your
//    Purpur 26.1.2-2587-dc4a255. If it fails to resolve, run `./gradlew showPaperVersions` to see
//    what's actually published and pin a specific one instead, e.g.
//    paperweight.paperDevBundle("26.1.2.build.67-stable").
// 2. Reobfuscation is intentionally not configured: Paper dropped it for 26.1+ (there's no more
//    obfuscated/Spigot mapping to reobf to), so the normal shadowJar/build tasks above are enough.
// 3. javaLauncher above is a guess (JDK 25) at what Minecraft 26.x's own build tooling needs. If
//    paperweightUserdevSetup still fails after this, re-run with --stacktrace and check the real
//    exception - it may need a different JDK version, or be an unrelated failure entirely.
