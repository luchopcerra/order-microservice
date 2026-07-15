plugins {
    kotlin("jvm") version "2.0.20"
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation("io.ktor:ktor-server-core-jvm:2.3.12")
    implementation("io.ktor:ktor-server-netty-jvm:2.3.12")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:2.3.12")
    implementation("io.ktor:ktor-serialization-jackson-jvm:2.3.12")
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("ch.qos.logback:logback-classic:1.5.7")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("orders.AppKt")
}

tasks.test {
    useJUnitPlatform()
}
