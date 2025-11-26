import com.rometools.rome.feed.module.SyModule
import com.rometools.rome.io.SyndFeedInput
import com.rometools.rome.io.XmlReader
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.time.Instant
import java.util.Locale
import java.util.regex.Pattern

private val logger = LoggerFactory.getLogger("r.c./RssParser")

fun parseRss(xml: String): List<ParsedArticle> {
    try {
        val input = SyndFeedInput()
        val feed = input.build(XmlReader(ByteArrayInputStream(xml.toByteArray())))
        val entries = feed.entries ?: emptyList()
        if (entries.isNotEmpty()) {
            return entries.mapNotNull { entry ->
                val link = entry.link ?: entry.uri ?: return@mapNotNull null
                val guid = entry.uri ?: entry.link
                val published = entry.publishedDate?.toInstant()
                ParsedArticle(guid = guid, link = link, title = entry.title, publishedAt = published)
            }
        }
    } catch (_: Exception) {
        // fall through to regex parsing
        logger.error("Error during parsing rss:\n${xml.drop(256)}...\nTrying alternative method...")
    }

    val itemPattern = Pattern.compile("<item[\\s\\S]*?</item>|<entry[\\s\\S]*?</entry>", Pattern.CASE_INSENSITIVE)
    val matcher = itemPattern.matcher(xml)
    val results = mutableListOf<ParsedArticle>()
    while (matcher.find()) {
        val block = matcher.group()
        val link = Regex("<link[^>]*>(.*?)</link>", RegexOption.IGNORE_CASE).find(block)?.groupValues?.getOrNull(1)
        val title = Regex("<title[^>]*>(.*?)</title>", RegexOption.IGNORE_CASE).find(block)?.groupValues?.getOrNull(1)
        if (link != null) {
            results.add(ParsedArticle(guid = link, link = link, title = title, publishedAt = null))
        }
    }
    return results
}

fun extractIntervalSeconds(feedXml: String, defaultInterval: Long): Long {
    try {
        val input = SyndFeedInput()
        val feed = input.build(XmlReader(ByteArrayInputStream(feedXml.toByteArray())))
        val ttl = Regex("<ttl>\\s*(\\d+)\\s*</ttl>", RegexOption.IGNORE_CASE).find(feedXml)?.groupValues?.getOrNull(1)?.toLongOrNull()
        if (ttl != null && ttl > 0) {
            return ttl.coerceIn(5, 1440) * 60
        }
        val module = feed.getModule(SyModule.URI) as? SyModule
        if (module != null) {
            val frequency = module.updateFrequency
            val period = module.updatePeriod?.lowercase(Locale.getDefault())
            val seconds = when (period) {
                "hourly" -> 3600L / (if (frequency > 0) frequency else 1)
                "daily" -> 86_400L / (if (frequency > 0) frequency else 1)
                "weekly" -> 604_800L / (if (frequency > 0) frequency else 1)
                else -> null
            }
            if (seconds != null) {
                return seconds.coerceIn(300, 86_400)
            }
        }
    } catch (_: Exception) {
    }
    return defaultInterval
}

data class ParsedArticle(
    val guid: String?,
    val link: String,
    val title: String?,
    val publishedAt: Instant?
)