package sh.hnet.news.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class FeedDatesTest {
    private fun at(text: String) = Instant.parse(text)

    @Test
    fun `rfc 822 and its variations`() {
        assertEquals(at("2026-11-13T12:29:48Z"), FeedDates.parse("Fri, 13 Nov 2026 13:29:48 +0100"))
        assertEquals(at("2026-11-13T13:29:48Z"), FeedDates.parse("Fri, 13 Nov 2026 13:29:48 GMT"))
        assertEquals(at("2026-11-13T18:29:00Z"), FeedDates.parse("13 Nov 2026 13:29 EST"))
        assertEquals(at("2026-11-13T11:29:48Z"), FeedDates.parse("Fri, 13 Nov 2026 13:29:48 CEST"))
        assertEquals(at("2026-11-13T13:29:48Z"), FeedDates.parse("Fri, 13 Nov 2026 13:29:48"))
        assertEquals(at("2026-11-13T00:00:00Z"), FeedDates.parse("13 November 2026"))
        assertEquals(at("2003-06-03T09:39:21Z"), FeedDates.parse("Tue, 03 Jun 03 09:39:21 GMT"))
        assertEquals(at("1999-12-31T23:59:00Z"), FeedDates.parse("Fri, 31 Dec 99 23:59 UT"))
        assertEquals(at("2026-11-13T08:00:00Z"), FeedDates.parse("Fri, 13 Nov 2026 13:30:00 +05:30"))
        assertEquals(at("2026-11-03T12:00:00Z"), FeedDates.parse("wt., 3 lis 2026 13:00:00 +0100"))
        assertEquals(at("2026-11-13T13:29:00Z"), FeedDates.parse("Nov 13, 2026 13:29"))
    }

    @Test
    fun `iso 8601 and its variations`() {
        assertEquals(at("2026-11-13T12:29:48Z"), FeedDates.parse("2026-11-13T13:29:48+01:00"))
        assertEquals(at("2026-11-13T13:29:48Z"), FeedDates.parse("2026-11-13T13:29:48Z"))
        assertEquals(at("2026-11-13T13:29:48Z"), FeedDates.parse("2026-11-13T13:29:48.123456Z"))
        assertEquals(at("2026-11-13T12:29:00Z"), FeedDates.parse("2026-11-13 13:29 +0100"))
        assertEquals(at("2026-11-13T13:29:48Z"), FeedDates.parse("2026-11-13T13:29:48"))
        assertEquals(at("2026-11-13T00:00:00Z"), FeedDates.parse("2026-11-13"))
        assertEquals(at("2026-11-13T15:29:48Z"), FeedDates.parse("  2026-11-13T13:29:48-02  "))
    }

    @Test
    fun `what is not a date is null`() {
        assertNull(FeedDates.parse(null))
        assertNull(FeedDates.parse(""))
        assertNull(FeedDates.parse("yesterday"))
        assertNull(FeedDates.parse("2026-13-45T99:99:99Z"))
        assertNull(FeedDates.parse("31 Feb 2026 10:00:00 GMT"))
    }
}
