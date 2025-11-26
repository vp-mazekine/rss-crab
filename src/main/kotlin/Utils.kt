import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import kotlin.random.Random

fun randomJitterSeconds(maxSeconds: Int): Long = if (maxSeconds <= 0) 0 else Random.nextInt(0, maxSeconds + 1).toLong()

fun nowInstant(): Instant = Instant.now()

suspend fun readFileIfExists(path: String): String? = withContext(Dispatchers.IO) {
    val file = File(path)
    if (file.exists()) file.readText() else null
}