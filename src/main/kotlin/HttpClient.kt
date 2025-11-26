import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

private var clientInstance: HttpClient? = null
private val logger = LoggerFactory.getLogger("HttpClient")

fun httpClient(config: AppConfig): HttpClient {
    val existing = clientInstance
    if (existing != null) return existing
    val client = HttpClient(CIO) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = config.scheduler.requestTimeoutMillis
        }
    }
    clientInstance = client
    return client
}

data class FetchResult(
    val httpStatus: Int?,
    val body: String?,
    val errorMessage: String?,
    val isTimeout: Boolean
)

suspend fun fetchFeedXml(config: AppConfig, url: String): FetchResult = withContext(Dispatchers.IO) {
    val client = httpClient(config)
    try {
        val response = client.get(url)
        val status = response.status.value
        return@withContext if (status in 200..299) {
            FetchResult(status, response.bodyAsText(), null, false)
        } else {
            FetchResult(status, null, "HTTP ${response.status.value} ${response.status.description}", false)
        }
    } catch (e: HttpRequestTimeoutException) {
        logger.warn("Request to $url timed out: ${e.message}")
        FetchResult(null, null, e.message, true)
    } catch (e: Exception) {
        logger.error("Request to $url failed", e)
        FetchResult(null, null, e.message, false)
    }
}