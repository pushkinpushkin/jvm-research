plugins {
    java
    application
    id("me.champeau.jmh") version "0.7.3"
}

group = "dev.pushkin"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

application {
    mainClass.set("dev.pushkin.jvmresearch.SandboxApp")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

jmh {
    warmupIterations.set(5)
    iterations.set(10)
    fork.set(2)
    benchmarkMode.set(listOf("thrpt", "avgt"))
    timeUnit.set("ms")
    resultFormat.set("JSON")
    resultsFile.set(layout.buildDirectory.file("reports/jmh/results.json").get().asFile)
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.13.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")

    jmh("org.openjdk.jmh:jmh-core:1.37")
    jmhAnnotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")
}
