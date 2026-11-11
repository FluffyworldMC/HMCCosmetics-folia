plugins {
    `java-library`
    `maven-publish`
}

java {
    withJavadocJar()
    withSourcesJar()
}

dependencies {
    compileOnly(fileTree("${rootDir}/lib") { include("*.jar") })
    compileOnly(libs.paper.api)
    compileOnly(libs.annotations)
    compileOnly(libs.commons.io) // Shipped by the server, not by us
    compileOnly(libs.hibiscuscommons)
    compileOnly(libs.placeholderapi)
    compileOnly(libs.modelengine)
    compileOnly(libs.nexo)
    compileOnly(libs.bundles.betterhud)
    compileOnly(libs.bettercommand) // BetterHud's api signatures reference it
    compileOnly(variantOf(libs.worldguard) { classifier("dist") }) {
        exclude(group = "org.bukkit")
        exclude(group = "com.google.guava")
        exclude(group = "com.google.code.gson")
        exclude(group = "it.unimi.dsi")
        exclude(group = "com.sk89q.jnbt")
        exclude(group = "org.enginehub.lin-bus.format")
    }
    // The WorldGuard distribution contains its core API, but WorldEdit is supplied separately.
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.3.9") { isTransitive = false }
    compileOnly("com.sk89q.worldedit:worldedit-core:7.3.9") { isTransitive = false }

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.mockito:mockito-core:5.15.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    implementation(libs.triumph.gui) { exclude("net.kyori") }
    implementation(libs.universal.scheduler)
}

// The server supplies these APIs in production; packet regression tests need them too.
configurations.testCompileOnly {
    extendsFrom(configurations.compileOnly.get())
}

configurations.testRuntimeOnly {
    extendsFrom(configurations.compileOnly.get())
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "${rootProject.group}"
            artifactId = rootProject.name
            version = "${rootProject.version}"

            from(components["java"])
        }
    }

    repositories {
        maven {
            name = "HibiscusMCRepository"
            url = uri(hibiscusRepository())

            credentials {
                username = System.getenv("REPO_USERNAME")
                password = System.getenv("REPO_PASSWORD")
            }
        }
    }
}

/** Target repository picked from the branch being built, defaulting to releases for local publishes */
fun hibiscusRepository(): String {
    val branch = System.getenv("GITHUB_REF")?.removePrefix("refs/heads/") ?: "local"
    return when {
        branch == "master" || branch == "local" -> "https://repo.hibiscusmc.com/releases/"
        branch.startsWith("dev") -> "https://repo.hibiscusmc.com/development/"
        else -> "https://repo.hibiscusmc.com/snapshots/"
    }
}
