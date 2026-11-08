plugins {
    java
}

group = "dev.twme"
version = "0.2.0"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("com.sk89q.worldedit:worldedit-core:7.4.2") { isTransitive = false }
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.4.2") { isTransitive = false }
    compileOnly("com.sk89q.worldedit.worldedit-libs:core:7.4.2") { isTransitive = false }
    // FAWE 專用類別放在 fawe 套件，只在 FAWE 存在時載入
    compileOnly("com.fastasyncworldedit:FastAsyncWorldEdit-Core:2.15.2") { isTransitive = false }

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> { options.encoding = "UTF-8"; options.release.set(21) }

tasks.processResources {
    val v = project.version.toString()
    inputs.property("version", v)
    filesMatching("plugin.yml") { expand("version" to v) }
}

tasks.test { useJUnitPlatform() }
