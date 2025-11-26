import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Instant
import kotlin.math.min

object Database {
    fun getConnection(config: AppConfig): Connection {
        val conn = if (config.db.user.isBlank() && config.db.password.isBlank()) {
            DriverManager.getConnection(config.db.url)
        } else {
            DriverManager.getConnection(config.db.url, config.db.user, config.db.password)
        }
        if (isSQLite(conn)) {
            conn.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
        }
        return conn
    }
}

private fun isSQLite(conn: Connection): Boolean = conn.metaData.url.lowercase().startsWith("jdbc:sqlite")

fun ensureTables(conn: Connection) {
    val sqlite = isSQLite(conn)
    conn.createStatement().use { stmt ->
        val feedsSql = if (sqlite) {
            """
            CREATE TABLE IF NOT EXISTS feeds (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                url TEXT NOT NULL UNIQUE,
                outlet_name TEXT,
                country_iso TEXT,
                topic TEXT,
                polling_interval_seconds INTEGER NOT NULL DEFAULT 3600,
                last_checked_at DATETIME,
                last_success_at DATETIME,
                consecutive_errors INTEGER NOT NULL DEFAULT 0,
                ignored BOOLEAN NOT NULL DEFAULT FALSE,
                next_run_at DATETIME,
                backoff_factor REAL NOT NULL DEFAULT 1.0
            );
            """.trimIndent()
        } else {
            """
            CREATE TABLE IF NOT EXISTS feeds (
                id SERIAL PRIMARY KEY,
                url TEXT NOT NULL UNIQUE,
                outlet_name TEXT,
                country_iso CHAR(2),
                topic TEXT,
                polling_interval_seconds INTEGER NOT NULL DEFAULT 3600,
                last_checked_at TIMESTAMPTZ,
                last_success_at TIMESTAMPTZ,
                consecutive_errors INTEGER NOT NULL DEFAULT 0,
                ignored BOOLEAN NOT NULL DEFAULT FALSE,
                next_run_at TIMESTAMPTZ,
                backoff_factor DOUBLE PRECISION NOT NULL DEFAULT 1.0
            );
            """.trimIndent()
        }

        val articlesSql = if (sqlite) {
            """
            CREATE TABLE IF NOT EXISTS articles (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                feed_id INTEGER NOT NULL REFERENCES feeds(id) ON DELETE CASCADE,
                guid TEXT,
                link TEXT NOT NULL,
                title TEXT,
                published_at DATETIME,
                created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                ai_processed BOOLEAN NOT NULL DEFAULT FALSE,
                UNIQUE(feed_id, guid),
                UNIQUE(feed_id, link)
            );
            """.trimIndent()
        } else {
            """
            CREATE TABLE IF NOT EXISTS articles (
                id BIGSERIAL PRIMARY KEY,
                feed_id INTEGER NOT NULL REFERENCES feeds(id) ON DELETE CASCADE,
                guid TEXT,
                link TEXT NOT NULL,
                title TEXT,
                published_at TIMESTAMPTZ,
                created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                ai_processed BOOLEAN NOT NULL DEFAULT FALSE,
                UNIQUE(feed_id, guid),
                UNIQUE(feed_id, link)
            );
            """.trimIndent()
        }

        val logSql = if (sqlite) {
            """
            CREATE TABLE IF NOT EXISTS feed_fetch_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                feed_id INTEGER NOT NULL REFERENCES feeds(id) ON DELETE CASCADE,
                requested_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                http_status INTEGER,
                error_message TEXT,
                source_version TEXT
            );
            """.trimIndent()
        } else {
            """
            CREATE TABLE IF NOT EXISTS feed_fetch_log (
                id BIGSERIAL PRIMARY KEY,
                feed_id INTEGER NOT NULL REFERENCES feeds(id) ON DELETE CASCADE,
                requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                http_status INTEGER,
                error_message TEXT,
                source_version TEXT
            );
            """.trimIndent()
        }

        stmt.execute(feedsSql)
        stmt.execute(articlesSql)
        stmt.execute(logSql)
    }
}

data class FeedRow(
    val id: Int,
    val url: String,
    val pollingIntervalSeconds: Long,
    val consecutiveErrors: Int,
    val ignored: Boolean,
    val backoffFactor: Double
)

fun importFeedsFromCsv(conn: Connection, config: AppConfig, feedsPath: String? = null) {
    val count = conn.createStatement().use { st ->
        st.executeQuery("SELECT COUNT(*) FROM feeds").use { rs ->
            rs.next();
            rs.getLong(1)
        }
    }
    val csv = File(feedsPath ?: "feeds.csv")
    if (count > 0L || !csv.exists()) return
    csv.bufferedReader().useLines { lines ->
        val iterator = lines.iterator()
        if (iterator.hasNext()) iterator.next() // skip header
        conn.prepareStatement(
            "INSERT INTO feeds (url, outlet_name, country_iso, topic, polling_interval_seconds, next_run_at, backoff_factor) VALUES (?,?,?,?,?,?,?)"
        ).use { ps ->
            iterator.forEachRemaining { line ->
                val parts = line.split(',')
                if (parts.size >= 5) {
                    val url = parts[0].trim()
                    val outlet = parts.getOrNull(1)?.trim().takeUnless { it.isNullOrEmpty() }
                    val country = parts.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() }
                    val topic = parts.getOrNull(3)?.trim()?.takeIf { it.isNotEmpty() }
                    val interval = parts.getOrNull(4)?.toLongOrNull() ?: config.scheduler.defaultIntervalSeconds
                    val jittered = Timestamp.from(nowInstant().plusSeconds(randomJitterSeconds(config.scheduler.jitterMaxSeconds)))
                    ps.setString(1, url)
                    ps.setString(2, outlet)
                    ps.setString(3, country)
                    ps.setString(4, topic)
                    ps.setLong(5, interval)
                    ps.setTimestamp(6, jittered)
                    ps.setDouble(7, 1.0)
                    ps.addBatch()
                }
            }
            ps.executeBatch()
        }
    }
}

fun selectDueFeedIds(conn: Connection): List<Int> {
    conn.prepareStatement(
        "SELECT id FROM feeds WHERE ignored = FALSE AND (next_run_at IS NULL OR next_run_at <= ?)"
    ).use { ps ->
        ps.setTimestamp(1, Timestamp.from(nowInstant()))
        ps.executeQuery().use { rs ->
            val ids = mutableListOf<Int>()
            while (rs.next()) {
                ids.add(rs.getInt("id"))
            }
            return ids
        }
    }
}

fun getFeedById(conn: Connection, id: Int): FeedRow? {
    conn.prepareStatement(
        "SELECT id, url, polling_interval_seconds, consecutive_errors, ignored, backoff_factor FROM feeds WHERE id = ?"
    ).use { ps ->
        ps.setInt(1, id)
        ps.executeQuery().use { rs ->
            return if (rs.next()) {
                FeedRow(
                    id = rs.getInt("id"),
                    url = rs.getString("url"),
                    pollingIntervalSeconds = rs.getLong("polling_interval_seconds"),
                    consecutiveErrors = rs.getInt("consecutive_errors"),
                    ignored = rs.getBoolean("ignored"),
                    backoffFactor = rs.getDouble("backoff_factor")
                )
            } else null
        }
    }
}

fun logFetch(conn: Connection, feedId: Int, result: FetchResult, config: AppConfig) {
    conn.prepareStatement(
        "INSERT INTO feed_fetch_log (feed_id, http_status, error_message, source_version) VALUES (?,?,?,?)"
    ).use { ps ->
        ps.setInt(1, feedId)
        if (result.httpStatus != null) ps.setInt(2, result.httpStatus) else ps.setNull(2, java.sql.Types.INTEGER)
        ps.setString(3, result.errorMessage)
        ps.setString(4, config.sourceVersion)
        ps.executeUpdate()
    }
}

fun insertArticles(conn: Connection, feedId: Int, articles: List<ParsedArticle>) {
    conn.prepareStatement(
        "INSERT INTO articles (feed_id, guid, link, title, published_at) VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING"
    ).use { ps ->
        for (article in articles) {
            ps.setInt(1, feedId)
            ps.setString(2, article.guid)
            ps.setString(3, article.link)
            ps.setString(4, article.title)
            if (article.publishedAt != null) ps.setTimestamp(5, Timestamp.from(article.publishedAt)) else ps.setNull(5, java.sql.Types.TIMESTAMP)
            ps.addBatch()
        }
        ps.executeBatch()
    }
}

fun updateFeedSuccess(conn: Connection, feedId: Int, nextRun: Instant, baseInterval: Long) {
    conn.prepareStatement(
        """
        UPDATE feeds SET last_checked_at = ?, last_success_at = ?, consecutive_errors = 0, ignored = FALSE,
        backoff_factor = 1.0, polling_interval_seconds = ?, next_run_at = ? WHERE id = ?
        """.trimIndent()
    ).use { ps ->
        val now = Timestamp.from(nowInstant())
        ps.setTimestamp(1, now)
        ps.setTimestamp(2, now)
        ps.setLong(3, baseInterval)
        ps.setTimestamp(4, Timestamp.from(nextRun))
        ps.setInt(5, feedId)
        ps.executeUpdate()
    }
}

fun updateFeedError(
    conn: Connection,
    feed: FeedRow,
    classification: ErrorClassification,
    config: AppConfig
): Boolean {
    val errorCount = when (classification) {
        ErrorClassification.GlobalTimeout -> feed.consecutiveErrors
        else -> feed.consecutiveErrors + 1
    }
    var backoff = feed.backoffFactor
    if (classification == ErrorClassification.Temporary || classification == ErrorClassification.Timeout) {
        backoff = min(backoff * config.errorHandling.backoffBaseMultiplier, config.errorHandling.backoffMaxFactor)
    }
    val baseInterval = feed.pollingIntervalSeconds
    val delaySeconds = when (classification) {
        ErrorClassification.Permanent, ErrorClassification.GlobalTimeout -> baseInterval
        ErrorClassification.Timeout, ErrorClassification.Temporary -> (baseInterval * backoff).toLong()
    } + randomJitterSeconds(config.scheduler.jitterMaxSeconds)

    val ignored = errorCount >= config.errorHandling.maxConsecutiveErrors
    conn.prepareStatement(
        """
        UPDATE feeds SET consecutive_errors = ?, ignored = ?, backoff_factor = ?, next_run_at = ? WHERE id = ?
        """.trimIndent()
    ).use { ps ->
        ps.setInt(1, errorCount)
        ps.setBoolean(2, ignored)
        ps.setDouble(3, backoff)
        ps.setTimestamp(4, Timestamp.from(nowInstant().plusSeconds(delaySeconds)))
        ps.setInt(5, feed.id)
        ps.executeUpdate()
    }
    return ignored
}

enum class ErrorClassification { Permanent, Temporary, Timeout, GlobalTimeout }