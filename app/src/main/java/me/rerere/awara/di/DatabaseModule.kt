package me.rerere.awara.di

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RenameColumn
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec
import me.rerere.awara.data.dao.DownloadDao
import me.rerere.awara.data.dao.DownloadTaskDao
import me.rerere.awara.data.dao.HistoryDao
import me.rerere.awara.data.download.DownloadManager
import me.rerere.awara.data.download.DownloadStorage
import me.rerere.awara.data.entity.DownloadItem
import me.rerere.awara.data.entity.DownloadTask
import me.rerere.awara.data.entity.HistoryItem
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val databaseModule = module {
    single { Room.databaseBuilder(androidContext(), AppDatabase::class.java, "awara.db").build() }

    single { DownloadStorage(androidContext()) }

    single { DownloadManager(androidContext(), get(), get(), get()) }
}

@Database(
    entities = [HistoryItem::class, DownloadItem::class, DownloadTask::class],
    version = 5,
    exportSchema = true,
    autoMigrations =
        [
            AutoMigration(from = 1, to = 2),
            AutoMigration(from = 2, to = 3, spec = AppDatabase.Migration2To3::class),
            AutoMigration(from = 3, to = 4),
            AutoMigration(from = 4, to = 5),
        ],
)
abstract class AppDatabase : RoomDatabase() {
    @RenameColumn(tableName = "downloaded_items", fromColumnName = "path", toColumnName = "uri")
    class Migration2To3 : AutoMigrationSpec

    abstract fun historyDao(): HistoryDao

    abstract fun downloadDao(): DownloadDao

    abstract fun downloadTaskDao(): DownloadTaskDao
}
