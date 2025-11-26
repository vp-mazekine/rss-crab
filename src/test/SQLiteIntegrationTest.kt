import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.sql.Statement
import java.sql.Timestamp
import kotlin.io.path.deleteIfExists
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SQLiteIntegrationTest {
    private lateinit var dbPath: String
    private lateinit var config: AppConfig

    @BeforeEach
    fun setup() {
        val path = Files.createTempFile("rss-crab-test", ".db")
        dbPath = path.toString()
        config = AppConfig(
            db = DbConfig(url = "jdbc:sqlite:$dbPath"),
            scheduler = SchedulerConfig(
                defaultIntervalSeconds = 300,
                maxParallelFetches = 1,
                requestTimeoutMillis = 15_000,
                jitterMaxSeconds = 0,
                connectivityCheckUrl = "https://www.google.com/generate_204"
            ),
            errorHandling = ErrorHandlingConfig(),
            telegram = TelegramConfig(),
            sourceVersion = "rss-crab test"
        )

        Database.getConnection(config).use { conn ->
            conn.autoCommit = false
            ensureTables(conn)
            conn.prepareStatement(
                "INSERT INTO feeds (url, polling_interval_seconds, consecutive_errors, ignored, next_run_at, backoff_factor) VALUES (?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS
            ).use { ps ->
                ps.setString(1, "https://e.vnexpress.net/rss/news.rss")
                ps.setLong(2, 300)
                ps.setInt(3, 0)
                ps.setBoolean(4, false)
                ps.setTimestamp(5, Timestamp.from(nowInstant()))
                ps.setDouble(6, 1.0)
                ps.executeUpdate()
            }
            conn.commit()
        }
    }

    @AfterEach
    fun teardown() {
        runCatching { Files.deleteIfExists(java.nio.file.Path.of(dbPath)) }
    }

    @Test
    fun `process feed stores articles and logs on sqlite`() = runBlocking {
        val feedId = Database.getConnection(config).use { conn ->
            conn.prepareStatement("SELECT id FROM feeds LIMIT 1").use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

        processFeed(feedId, config)

        Database.getConnection(config).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM feed_fetch_log").use { rs ->
                    rs.next()
                    assertTrue(rs.getLong(1) >= 1, "fetch log should have at least one entry")
                }
                val latestLog = st.executeQuery("SELECT http_status, error_message FROM feed_fetch_log ORDER BY id DESC LIMIT 1")
                latestLog.use { rs ->
                    val status = if (rs.next()) rs.getInt("http_status") else null
                    val error = rs.getString("error_message")
                    assumeTrue(status != null && status in 200..299, "Feed unreachable during test: status=$status error=$error")
                }
                st.executeQuery("SELECT COUNT(*) FROM articles").use { rs ->
                    rs.next()
                    assertTrue(rs.getLong(1) >= 1, "articles should be stored after successful fetch")
                }
            }
            val feed = getFeedById(conn, feedId)!!
            assertEquals(0, feed.consecutiveErrors)
            assertFalse(feed.ignored)
        }
    }
}