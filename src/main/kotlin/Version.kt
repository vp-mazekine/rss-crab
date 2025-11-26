const val APP_VERSION = "0.1.0"
const val APP_NAME = "rss-crab"

fun appDisplayVersion(): String {
    val implementationVersion = Version::class.java.`package`?.implementationVersion
    val version = implementationVersion?.takeIf { it.isNotBlank() } ?: APP_VERSION
    return "$APP_NAME v$version"
}

private object Version