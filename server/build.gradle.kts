// Strategos server (SDD v4.0): a Ktor JVM service that runs the same on-device engine as the app.
// The engine is compiled straight from the app's sources (domain/ and framework/ import nothing from Android),
// so there is one copy of every solver and the app and the server can never drift apart.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
}

kotlin { jvmToolchain(17) }

val engine = listOf("domain", "framework").map { "../app/src/main/kotlin/com/drkkr/strategos/$it" }
sourceSets["main"].kotlin.srcDirs(listOf("src/main/kotlin") + engine)

application {
    mainClass.set("com.drkkr.strategos.server.ApplicationKt")
}

val ktor = "2.3.12"
dependencies {
    implementation("io.ktor:ktor-server-core-jvm:$ktor")
    implementation("io.ktor:ktor-server-netty-jvm:$ktor")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktor")
    implementation("io.ktor:ktor-server-cors-jvm:$ktor")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktor")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.xerial:sqlite-jdbc:3.46.1.0")
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("org.slf4j:slf4j-simple:2.0.13")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:1.9.24")
    testImplementation("org.jetbrains.kotlin:kotlin-reflect:1.9.24")
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktor")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

tasks.test { useJUnit() }
