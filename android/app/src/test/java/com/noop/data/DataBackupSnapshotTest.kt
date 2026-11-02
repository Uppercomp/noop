package com.noop.data

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DataBackupSnapshotTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun snapshotIncludesWalAndStaysUnchangedAfterFurtherWrites() {
        val source = File(temp.root, "live.sqlite")
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
                .name(source.absolutePath).callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) { db.execSQL("CREATE TABLE sample(id INTEGER PRIMARY KEY, value TEXT)") }
                    override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
                }).build())
        helper.setWriteAheadLoggingEnabled(true)
        try {
            val live = helper.writableDatabase
            live.query("PRAGMA wal_autocheckpoint=0").close()
            live.execSQL("INSERT INTO sample VALUES(1,'pending WAL commit')")
            assertTrue(File(source.path + "-wal").length() > 0)
            val staged = DataBackup.createBackupSnapshot(live, source, temp.root) {
                fail("Modern SQLite must use its transactional snapshot rather than copy the main file")
            }
            live.execSQL("INSERT INTO sample VALUES(2,'later commit')")
            SQLiteDatabase.openDatabase(staged.path, null, SQLiteDatabase.OPEN_READONLY).use { backup ->
                backup.rawQuery("PRAGMA integrity_check", null).use { c -> assertTrue(c.moveToFirst()); assertEquals("ok", c.getString(0)) }
                backup.rawQuery("SELECT id,value FROM sample ORDER BY id", null).use { c ->
                    assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0)); assertEquals("pending WAL commit", c.getString(1))
                    assertFalse(c.moveToNext())
                }
            }
            assertTrue(staged.delete())
        } finally { helper.close() }
    }
}
