import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import java.io.File

data class DbConfig(
    val url: String,
    val user: String = "",
    val password: String = ""
)

data class SchedulerConfig(
    val defaultIntervalSeconds: Long = 3600,
    val maxParallelFetches: Int = 5,
    val requestTimeoutMillis: Long = 10_000,
    val jitterMaxSeconds: Int = 300,
    val connectivityCheckUrl: String = "https://www.google.com/",
    val dbCheckIntervalMillis: Long = 5_000
)

data class ErrorHandlingConfig(
    val maxConsecutiveErrors: Int = 3,
    val backoffBaseMultiplier: Double = 2.0,
    val backoffMaxFactor: Double = 32.0
)

data class TelegramConfig(
    val enabled: Boolean = false,
    val botToken: String = "",
    val chatId: String = ""
)

data class AppConfig(
    val db: DbConfig,
    val scheduler: SchedulerConfig = SchedulerConfig(),
    val errorHandling: ErrorHandlingConfig = ErrorHandlingConfig(),
    val telegram: TelegramConfig = TelegramConfig(),
    val sourceVersion: String = appDisplayVersion()
)

fun loadConfig(configPath: String? = null): AppConfig {
    val configFile = configPath?.let(::File) ?: File("application.conf")
    val config = ConfigFactory.parseFile(configFile).withFallback(ConfigFactory.load())
    val root = config.getConfig("rss-crab")
    val db = root.getConfig("db")
    val scheduler = root.getConfigOrNull("scheduler")
    val errorHandling = root.getConfigOrNull("errorHandling")
    val telegram = root.getConfigOrNull("telegram")

    return AppConfig(
        db = DbConfig(
            url = db.getString("url"),
            user = db.getStringOrNull("user") ?: "",
            password = db.getStringOrNull("password") ?: ""
        ),
        scheduler = SchedulerConfig(
            defaultIntervalSeconds = scheduler?.getLong("defaultIntervalSeconds") ?: SchedulerConfig().defaultIntervalSeconds,
            maxParallelFetches = scheduler?.getInt("maxParallelFetches") ?: SchedulerConfig().maxParallelFetches,
            requestTimeoutMillis = scheduler?.getLong("requestTimeoutMillis") ?: SchedulerConfig().requestTimeoutMillis,
            jitterMaxSeconds = scheduler?.getInt("jitterMaxSeconds") ?: SchedulerConfig().jitterMaxSeconds,
            connectivityCheckUrl = scheduler?.getString("connectivityCheckUrl") ?: SchedulerConfig().connectivityCheckUrl,
            dbCheckIntervalMillis = scheduler?.getLong("dbCheckIntervalMillis") ?: SchedulerConfig().dbCheckIntervalMillis
        ),
        errorHandling = ErrorHandlingConfig(
            maxConsecutiveErrors = errorHandling?.getInt("maxConsecutiveErrors") ?: ErrorHandlingConfig().maxConsecutiveErrors,
            backoffBaseMultiplier = errorHandling?.getDouble("backoffBaseMultiplier") ?: ErrorHandlingConfig().backoffBaseMultiplier,
            backoffMaxFactor = errorHandling?.getDouble("backoffMaxFactor") ?: ErrorHandlingConfig().backoffMaxFactor
        ),
        telegram = TelegramConfig(
            enabled = telegram?.getBoolean("enabled") ?: TelegramConfig().enabled,
            botToken = telegram?.getString("botToken") ?: TelegramConfig().botToken,
            chatId = telegram?.getString("chatId") ?: TelegramConfig().chatId
        ),
        sourceVersion = root.getStringOrNull("sourceVersion") ?: appDisplayVersion()
    )
}

private fun Config.getConfigOrNull(path: String): Config? = if (hasPath(path)) getConfig(path) else null
private fun Config.getStringOrNull(path: String): String? = if (hasPath(path)) getString(path) else null