plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
    jacoco
}

group = "org.github.nafania217518"
version = "0.0.1-SNAPSHOT"
description = "claudius-backend"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

extra["springAiVersion"] = "2.0.1"

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    implementation("org.springframework.boot:spring-boot-starter-webflux")

    // M2: per-workspace SQLite history. starter-jdbc coexists with WebFlux; blocking
    // JdbcTemplate calls run off the event loop via boundedElastic.
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    // Not Boot-managed → pinned explicitly. ponytail: one SQLiteDataSource per workspace
    // is enough for chat volume; a shared pool / R2DBC comes if throughput matters.
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")

    // M10: plain model modules, no starters — ChatModelFactory builds one model per provider,
    // so a missing API key never breaks startup.
    implementation("org.springframework.ai:spring-ai-ollama")
    implementation("org.springframework.ai:spring-ai-openai")
    implementation("org.springframework.ai:spring-ai-anthropic")
    implementation("tools.jackson.module:jackson-module-kotlin")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webflux-test")
    testImplementation("io.projectreactor:reactor-test")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("io.kotest:kotest-assertions-core:6.2.3")

    testImplementation("io.mockk:mockk-jvm:1.14.11")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}")
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

tasks.test {
    // M8: Landlock tests need Linux + landrun; they run via landlockTest instead.
    useJUnitPlatform { excludeTags("landlock") }
    finalizedBy(tasks.jacocoTestReport)
}

// M8: `./gradlew landlockTest` in a Linux container with landrun. Not part of `check`.
val landlockTest by tasks.registering(Test::class) {
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("landlock") }
}

// Core functionality lives in the `chat` package; scope the report there so the
// coverage number reflects the feature under test, not the trivial @SpringBootTest
// application class.
tasks.withType<JacocoReport> {
    dependsOn(tasks.test)
    sourceDirectories.setFrom(layout.projectDirectory.dir("src/main/kotlin"))
    reports {
        html.required.set(true)
        xml.required.set(true)
     }
}

// CLAUDE.md rule 5: >= 90% per core module, enforced by the build (not by reading HTML).
// Package-scoped, so the root @SpringBootApplication entry point is outside the rule.
tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule {
            element = "PACKAGE"
            includes = listOf(
                "org.github.nafania217518.claudius.chat*",
                "org.github.nafania217518.claudius.workspace*",
                "org.github.nafania217518.claudius.provider*",
            )
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.90".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}
