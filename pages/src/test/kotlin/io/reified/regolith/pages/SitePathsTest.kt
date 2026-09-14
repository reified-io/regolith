package io.reified.regolith.pages

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SitePathsTest {
    @Test
    fun `a path is cleaned to what the manifest will hold`() {
        assertEquals("index.html", SitePaths.clean("index.html"))
        assertEquals("assets/app.js", SitePaths.clean("./assets/app.js"))
        assertEquals("assets/app.js", SitePaths.clean("assets//app.js"))
    }

    // a published tree gets `.git` and `.env` by accident far more often than anything needs them
    @Test
    fun `nothing outside the site, and no dot segment, can be published`() {
        for (bad in listOf("../secrets", "a/../../b", "/etc/passwd", ".env", "build/.git/config", "a\\b", "")) {
            assertFailsWith<PagesError.Invalid>("`$bad` was accepted") { SitePaths.clean(bad) }
        }
    }

    @Test
    fun `a request resolves to the manifest key it asks for`() {
        assertEquals("index.html", SitePaths.requested("/"))
        assertEquals("about/index.html", SitePaths.requested("/about/"))
        assertEquals("assets/app.js", SitePaths.requested("/assets/app.js"))
        assertEquals("about/index.html", SitePaths.directoryIndex("about"))
    }

    @Test
    fun `a type comes from the extension and never from the publisher`() {
        assertEquals("text/html; charset=utf-8", ContentTypes.of("index.html"))
        assertEquals("image/png", ContentTypes.of("assets/logo.png"))
        assertEquals("application/octet-stream", ContentTypes.of("data.unknown"))
    }
}
