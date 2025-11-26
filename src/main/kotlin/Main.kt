import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

private val logger = LoggerFactory.getLogger("rss-crab")

fun main(args: Array<String>) = runBlocking {
    if (args.any { it == "--version" || it == "-v" }) {
        logger.info(appDisplayVersion())
        return@runBlocking
    }

    val configFlagIndex = args.indexOfFirst { it == "--config" || it == "-c" }
    val configPath = when {
        configFlagIndex == -1 -> null
        configFlagIndex == args.lastIndex -> {
            logger.error("Missing path after ${args[configFlagIndex]}")
            exitProcess(1)
        }
        else -> args[configFlagIndex + 1]
    }

    val config = loadConfig(configPath)
    logger.info("Starting rss-crab ${config.sourceVersion}")
    logger.info("Using configuration from ${configPath ?: "classpath/application.conf"}")
    try {
        withContext(Dispatchers.IO) {
            Database.getConnection(config).use { conn ->
                ensureTables(conn)
                importFeedsFromCsv(conn, config)
            }
        }
        val semaphore = Semaphore(config.scheduler.maxParallelFetches)
        while (true) {
            val dueFeedIds = withContext(Dispatchers.IO) {
                Database.getConnection(config).use { conn ->
                    selectDueFeedIds(conn)
                }
            }
            logger.info("Found ${dueFeedIds.size} due feed(s)")
            for (id in dueFeedIds) {
                launch {
                    semaphore.withPermit {
                        try {
                            processFeed(id, config)
                        } catch (e: Exception) {
                            logger.error("Failed to process feed $id", e)
                        }
                    }
                }
            }
            delay(5000)
        }
    } catch (e: Exception) {
        logger.error("rss-crab crashed", e)
        sendTelegramMessage(config, "rss-crab crashed: ${e.message}")
        exitProcess(1)
    }
}