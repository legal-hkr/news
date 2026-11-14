package sh.hnet.news.content

import org.junit.Assert.assertEquals
import org.junit.Test

class FoldTest {
    @Test
    fun `case and diacritics go, every character stays one`() {
        assertEquals("lodz, zazolc gesla jazn", Fold.fold("Łódź, Zażółć gęślą jaźń"))
        assertEquals("strase, aro, cour", Fold.fold("Straße, Ærø, Cœur"))
        val text = "Żółw – Ångström ñ 😀 end"
        assertEquals(text.length, Fold.fold(text).length)
    }

    @Test
    fun `a query is trimmed and its spaces collapsed`() {
        assertEquals("sad najwyzszy", Fold.query("  Sąd   Najwyższy "))
        assertEquals("", Fold.query("   "))
    }
}
