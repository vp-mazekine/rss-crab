import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant

@Serializable
data class HealthResponse(
    val status: String,
    val timestamp: String,
    val uptimeSeconds: Long,
    val version: String
)

fun startHealthApi(config: AppConfig, startedAt: Instant): ApplicationEngine? {
    if (!config.api.enabled) return null

    val environment = applicationEngineEnvironment {
        module {
            install(ContentNegotiation) {
                json()
            }
            routing {
                get("/health") {
                    val uptimeSeconds = Duration.between(startedAt, nowInstant()).seconds
                    call.respond(
                        HealthResponse(
                            status = "ok",
                            timestamp = nowInstant().toString(),
                            uptimeSeconds = uptimeSeconds,
                            version = config.sourceVersion
                        )
                    )
                }
            }
        }
        config.api.hosts.ifEmpty { listOf("0.0.0.0") }.forEach { host ->
            connector {
                this.host = host
                port = config.api.port
            }
        }
    }
    val server = embeddedServer(CIO, environment)
    server.start(wait = false)
    return server
}
