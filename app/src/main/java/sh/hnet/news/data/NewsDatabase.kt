package sh.hnet.news.data

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver

@Database(entities = [Folder::class, Feed::class], version = 1)
abstract class NewsDatabase : RoomDatabase() {
    abstract fun feedDao(): FeedDao

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
