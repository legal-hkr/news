package sh.hnet.news.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.news.content.Block
import sh.hnet.news.content.Blocks
import sh.hnet.news.content.RichText

class BackupTest {
    private val body = Blocks.encode(listOf(Block.Paragraph(RichText("Zażółć gęślą jaźń")), Block.Image("https://site.example/a.jpg", 640, 480)))

    private val file = BackupFile(
        appVersion = "1.0.0",
        appVersionCode = 1,
        createdAt = "2026-11-14T10:00:00Z",
        settings = BackupSettings(source = "folder", sourceFolder = "Law", unreadOnly = false, textSize = 115),
        folders = listOf(BackupFolder("Law", 1, collapsed = true)),
        feeds = listOf(BackupFeed(url = "https://site.example/feed", title = "Site", folder = "Law", fullPage = true, keepDays = 90)),
        saved = listOf(BackupArticle(feedUrl = "https://site.example/feed", feedTitle = "Site", guid = "g1", title = "Title", published = 1_794_000_000_000, read = true, page = Backup.body(body))),
    )

    @Test
    fun `a backup survives its file, the saved article's blocks with it`() {
        val check = Backup.check(Backup.encode(file))
        assertTrue(check is BackupCheck.Valid)
        val read = (check as BackupCheck.Valid).backup
        assertEquals(file, read)
        assertEquals(Blocks.decode(body), Blocks.decode(read.saved.single().page.toString()))
    }

    @Test
    fun `what is not a backup, and one from a later format, are told apart`() {
        assertTrue(Backup.check("not json") is BackupCheck.NotOurs)
        assertTrue(Backup.check("""{"format":"sh.hnet.music.backup","formatVersion":1}""") is BackupCheck.NotOurs)
        assertEquals(BackupCheck.Newer(2), Backup.check("""{"format":"sh.hnet.news.backup","formatVersion":2}"""))
    }

    @Test
    fun `fields of a later version are passed over, absent ones take their defaults`() {
        val check = Backup.check("""{"format":"sh.hnet.news.backup","formatVersion":1,"appVersion":"9","appVersionCode":9,"createdAt":"x","later":true,"settings":{"later":1}}""")
        assertEquals(BackupSettings(), (check as BackupCheck.Valid).backup.settings)
    }
}
