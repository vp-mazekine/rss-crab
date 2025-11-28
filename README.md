# rss-crab

rss-crab is a Kotlin coroutine-based RSS poller that persists feed metadata and articles to a SQL database. It ships with a scheduler, backoff-aware error handling, and optional Telegram alerts to notify when feeds are ignored after repeated failures.

## Requirements
- JDK 21+
- Gradle (the repository includes the Gradle wrapper)
- PostgreSQL or SQLite database (URL-driven)

## Configuration
Configuration is read from `application.conf` (or another path passed with `--config`/`-c`). Values fall back to `src/main/resources/application.conf`.

```hocon
rss-crab {
  db {
    url = "jdbc:postgresql://localhost:5432/rss"
    user = "rss_user"
    password = "secret"
  }

  scheduler {
    defaultIntervalSeconds = 3600
    maxParallelFetches = 5
    requestTimeoutMillis = 10000
    jitterMaxSeconds = 300
    connectivityCheckUrl = "https://www.google.com/"
  }

  errorHandling {
    maxConsecutiveErrors = 3
    backoffBaseMultiplier = 2.0
    backoffMaxFactor = 32.0
  }

  telegram {
    enabled = false
    botToken = ""
    chatId = ""
  }

  articles {
    maxAgeDays = 30
  }

  sourceVersion = "rss-crab v0.1.0"
}
```

- The database URL determines whether PostgreSQL or SQLite is used; SQLite URLs enable foreign keys automatically.
- On first run, `feeds.csv` (if present) seeds the `feeds` table once it is empty. Each row should include `url,outlet_name,country_iso,topic,polling_interval_seconds`. You can point to a different seed file with the `--feeds-file`/`-f` CLI flag.
- Set `telegram.enabled` to `true` with valid credentials to receive crash/ignore notifications.
- Entries older than `articles.maxAgeDays` are ignored during insertion, allowing you to tune how far back to import historical content.

## Usage
### Running from source
- Print version: `./gradlew run --args="--version"`
- Run with default config: `./gradlew run`
- Run with a custom config: `./gradlew run --args="--config /path/to/app.conf"`
- Run with a custom feeds seed file: `./gradlew run --args="--feeds-file /path/to/feeds.csv"`

### Building a distributable JAR
- Create a fat JAR: `./gradlew shadowJar`
- Execute the packaged app: `java -jar build/libs/rss-crab-all.jar --config /path/to/app.conf`

### Runtime behavior
- The main loop polls for due feeds every 5 seconds, respecting per-feed intervals and jitter.
- Successful fetches parse RSS via Rome (with a regex fallback) and insert new articles while updating scheduling state.
- Failed fetches apply exponential backoff, mark feeds ignored after repeated errors, and optionally send Telegram alerts.

## Source code tour
- `src/main/kotlin/Main.kt`: entrypoint; loads config, ensures tables, imports CSV feeds, and orchestrates the scheduler loop.
- `src/main/kotlin/Config.kt`: typed config loader using Typesafe Config with defaults for scheduler, error handling, and Telegram.
- `src/main/kotlin/Database.kt`: database helpers for table creation, seeding, selecting due feeds, logging fetches, and updating feed state.
- `src/main/kotlin/Scheduler.kt`: feed processing pipeline, including connectivity checks, fetch classification, backoff, and notifications.
- `src/main/kotlin/HttpClient.kt`: shared Ktor HTTP client with request timeouts and fetch routine returning structured results.
- `src/main/kotlin/RssParser.kt`: RSS/Atom parsing via Rome with TTL/updateFrequency handling and regex fallback for resilience.
- `src/main/kotlin/Telegram.kt`: optional Telegram messenger for crash/ignore alerts.
- `src/main/kotlin/Utils.kt`: small utilities for jitter, timestamps, and safe file reads.
- `src/main/kotlin/Version.kt`: app name/version helpers for CLI output and fetch logging.

## Testing
Run the test suite with:

```bash
./gradlew test
```
