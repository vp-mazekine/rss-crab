import kotlin.test.Test
import kotlin.test.assertEquals

class VersionTest {
    @Test
    fun `displays default version string`() {
        assertEquals("$APP_NAME v$APP_VERSION", appDisplayVersion())
    }
}
