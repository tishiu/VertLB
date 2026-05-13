plugins {
    java
    application
}

group = "io.vertilb"
version = "1.0.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

val vertxVersion = "4.5.10"
val junitVersion = "5.10.2"

dependencies {
    implementation(platform("io.vertx:vertx-stack-depchain:$vertxVersion"))
    implementation("io.vertx:vertx-core")
    implementation("io.vertx:vertx-web-client")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.1")
    implementation("org.slf4j:slf4j-api:2.0.13")
    implementation("ch.qos.logback:logback-classic:1.5.6")

    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("io.vertx:vertx-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("io.vertilb.VertiLB")
}

tasks.test {
    useJUnitPlatform()
}
