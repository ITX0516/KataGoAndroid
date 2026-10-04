plugins {
    kotlin("jvm") version "1.9.10"
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
}
