import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.system.exitProcess

fun main(args: Array<String>) = runBlocking {
    if (args.any { it == "--version" || it == "-v" }) {
        println(appDisplayVersion())
        return@runBlocking
    }

    val configFlagIndex = args.indexOfFirst { it == "--config" || it == "-c" }
    val configPath = when {
        configFlagIndex == -1 -> null
        configFlagIndex == args.lastIndex -> {
            System.err.println("Missing path after ${args[configFlagIndex]}")
            exitProcess(1)
        }
        else -> args[configFlagIndex + 1]
    }

    val config = loadConfig(configPath)
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
            for (id in dueFeedIds) {
                launch {
                    semaphore.withPermit {
                        try {
                            processFeed(id, config)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
            delay(5000)
        }
    } catch (e: Exception) {
        e.printStackTrace()
        sendTelegramMessage(config, "rss-crab crashed: ${e.message}")
        exitProcess(1)
    }
}