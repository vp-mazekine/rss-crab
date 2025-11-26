import kotlin.test.Test
import kotlin.test.assertEquals

class VersionTest {
    @Test
    fun `displays default version string`() {
        assertEquals("rss-crab v0.1.0", appDisplayVersion())
    }
}