package sh.hnet.news.data

import android.content.Context
import androidx.room3.AutoMigration
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver

@Database(
    entities = [Folder::class, Feed::class, Article::class, ArticleContent::class],
    version = 3,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
abstract class NewsDatabase : RoomDatabase() {
    abstract fun feedDao(): FeedDao
    abstract fun articleDao(): ArticleDao

    companion object {
        @Volatile private var instance: NewsDatabase? = null

        fun get(context: Context): NewsDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder<NewsDatabase>(context.applicationContext, "news.db")
                .setDriver(AndroidSQLiteDriver())
                .build()
                .also { instance = it }
        }
    }
}
