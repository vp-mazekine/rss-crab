import io.ktor.client.request.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

suspend fun sendTelegramMessage(config: AppConfig, text: String) {
    val tg = config.telegram
    if (!tg.enabled || tg.botToken.isBlank() || tg.chatId.isBlank()) return
    val encoded = URLEncoder.encode(text, StandardCharsets.UTF_8)
    val url = "https://api.telegram.org/bot${tg.botToken}/sendMessage?chat_id=${tg.chatId}&text=$encoded"
    try {
        withContext(Dispatchers.IO) {
            httpClient(config).post(url)
        }
    } catch (_: Exception) {
        // ignore telegram failures
    }
}