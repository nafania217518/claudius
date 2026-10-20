package org.github.nafania217518.claudius

import org.springframework.boot.autoconfigure.SpringBootApplication
// ponytail: Spring Boot 4 moved the auto-config to spring-boot-jdbc (a new module the
// starter-jdbc pulls in) — the old autoconfigure.jdbc FQN no longer resolves.
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.runApplication

// ponytail: starter-jdbc is on the classpath (WorkspaceRegistry/SessionStore own their
// SQLite datasources), so the auto DataSource — which wants spring.datasource.url —
// is excluded to keep contextLoads green.
@SpringBootApplication(exclude = [DataSourceAutoConfiguration::class])
class ClaudiusBackendApplication

fun main(args: Array<String>) {
    runApplication<ClaudiusBackendApplication>(*args)
}
