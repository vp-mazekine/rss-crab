import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun checkConnectivity(config: AppConfig): Boolean = withContext(Dispatchers.IO) {
    try {
        val result = fetchFeedXml(config, config.scheduler.connectivityCheckUrl)
        result.httpStatus in 200..399
    } catch (_: Exception) {
        false
    }
}

suspend fun processFeed(feedId: Int, config: AppConfig) {
    val feed = withContext(Dispatchers.IO) {
        Database.getConnection(config).use { conn ->
            getFeedById(conn, feedId)
        }
    } ?: return
    if (feed.ignored) return

    val fetchResult = fetchFeedXml(config, feed.url)

    withContext(Dispatchers.IO) {
        Database.getConnection(config).use { conn ->
            conn.autoCommit = false
            try {
                val latestFeed = getFeedById(conn, feedId)
                if (latestFeed == null || latestFeed.ignored) {
                    logFetch(conn, feedId, fetchResult, config)
                    conn.commit()
                    return@use
                }
                logFetch(conn, feedId, fetchResult, config)

                val baseInterval = latestFeed.pollingIntervalSeconds

                if (fetchResult.body != null && (fetchResult.httpStatus ?: 0) in 200..299) {
                    val articles = parseRss(fetchResult.body)
                    if (articles.isNotEmpty()) {
                        val effectiveInterval = extractIntervalSeconds(fetchResult.body, baseInterval)
                        insertArticles(conn, feedId, articles)
                        val nextRun = nowInstant().plusSeconds(effectiveInterval + randomJitterSeconds(config.scheduler.jitterMaxSeconds))
                        updateFeedSuccess(conn, feedId, nextRun, effectiveInterval)
                    } else {
                        val ignored = updateFeedError(conn, latestFeed, ErrorClassification.Permanent, config)
                        if (ignored) sendTelegramMessage(config, "Feed ignored after parse errors: ${latestFeed.url}")
                    }
                } else {
                    val classification = when {
                        fetchResult.httpStatus != null && fetchResult.httpStatus in 400..499 -> ErrorClassification.Permanent
                        fetchResult.isTimeout -> {
                            val connectivityOk = checkConnectivity(config)
                            if (!connectivityOk) ErrorClassification.GlobalTimeout else ErrorClassification.Timeout
                        }
                        else -> ErrorClassification.Temporary
                    }
                    val ignored = updateFeedError(conn, latestFeed, classification, config)
                    if (classification == ErrorClassification.GlobalTimeout) {
                        println("Global connectivity issue detected, scheduling normally")
                    }
                    if (ignored) {
                        sendTelegramMessage(
                            config,
                            "Feed ignored after ${config.errorHandling.maxConsecutiveErrors} errors (status ${fetchResult.httpStatus ?: "?"}): ${latestFeed.url}"
                        )
                    }
                }
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            }
        }
    }
}