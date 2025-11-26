import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    kotlin("jvm") version "2.0.21"
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("io.ktor:ktor-client-core:2.3.12")
    implementation("io.ktor:ktor-client-cio:2.3.12")
    implementation("com.typesafe:config:1.4.3")
    implementation("com.rometools:rome:2.1.0")
    implementation("org.postgresql:postgresql:42.7.3")
    implementation("ch.qos.logback:logback-classic:1.5.12")
    implementation("org.xerial:sqlite-jdbc:3.46.0.1")

    testImplementation(kotlin("test"))
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

application {
    mainClass.set("MainKt")
}

tasks.withType<ShadowJar> {
    archiveBaseName.set("rss-crab")
    archiveClassifier.set("all")
    manifest {
        attributes(
            "Main-Class" to "MainKt",
            "Implementation-Title" to "rss-crab",
            "Implementation-Version" to project.version
        )
    }
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}