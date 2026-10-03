package com.personal.screenmacro

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.personal.screenmacro.core.*
import com.personal.screenmacro.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PriorityMigrationTest {
    private suspend fun <T> MacroDatabase.useDatabase(block: suspend (MacroDatabase) -> T): T = try { block(this) } finally { close() }
    @Test fun versionOneUpgradePreservesMacrosAndPersistsOrderAndSelection() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        val newer = Macro(id = "newer", name = "입장", query = "입장하기", createdAt = 10, updatedAt = 20)
        val older = Macro(id = "older", name = "확인", query = "확인", createdAt = 5, updatedAt = 10, intervalMs = 1700, preDelayMs = 200, postDelayMs = 300)
        val image = Macro(id = "image", type = RecognitionType.IMAGE, name = "기준 이미지", templatePath = "existing.png", referenceWidth = 1080, referenceHeight = 2340, createdAt = 2, updatedAt = 3)
        val legacySql = "CREATE TABLE macros (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, type TEXT NOT NULL, `left` REAL NOT NULL, top REAL NOT NULL, `right` REAL NOT NULL, bottom REAL NOT NULL, interval INTEGER NOT NULL, pre INTEGER NOT NULL, post INTEGER NOT NULL, query TEXT NOT NULL, mode TEXT NOT NULL, ignoreCase INTEGER NOT NULL, template TEXT, threshold REAL NOT NULL, width INTEGER NOT NULL, height INTEGER NOT NULL, rotation INTEGER NOT NULL, created INTEGER NOT NULL, updated INTEGER NOT NULL, targetPackage TEXT NOT NULL)"
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(legacySql)
            for (m in listOf(older, newer, image)) {
                val values = ContentValues().apply {
                    put("id", m.id); put("name", m.name); put("type", m.type.name)
                    put("left", 0.0); put("top", 0.0); put("right", 1.0); put("bottom", 1.0)
                    put("interval", m.intervalMs); put("pre", m.preDelayMs); put("post", m.postDelayMs)
                    put("query", m.query); put("mode", "EXACT"); put("ignoreCase", 0); if (m.templatePath == null) putNull("template") else put("template", m.templatePath)
                    put("threshold", .9); put("width", m.referenceWidth); put("height", m.referenceHeight); put("rotation", m.referenceRotation)
                    put("created", m.createdAt); put("updated", m.updatedAt); put("targetPackage", "com.nexon.devcat.mm")
                }
                assertTrue(db.insertOrThrow("macros", null, values) >= 0)
            }
            db.version = 1
        }
        fun open() = Room.databaseBuilder(context, MacroDatabase::class.java, name).addMigrations(MIGRATION_1_2).build()
        try {
            open().useDatabase { db ->
                val rows = db.macros().all()
                assertEquals(listOf("newer", "older", "image"), rows.map { it.id })
                assertEquals(listOf(0, 1, 2), rows.map { it.priority })
                assertTrue(rows.none { it.selected })
                assertEquals(newer, rows[0].model().copy(priority = Int.MAX_VALUE))
                assertEquals(older, rows[1].model().copy(priority = Int.MAX_VALUE))
                assertEquals(image, rows[2].model().copy(priority = Int.MAX_VALUE))
                assertEquals("com.nexon.devcat.mm", rows[0].targetPackage)
                db.macros().select("older", true)
                db.macros().reorder(listOf("older", "newer", "image"))
                assertTrue(runCatching { db.macros().reorder(listOf("older", "older")) }.isFailure)
            }
            open().useDatabase { db ->
                val rows = db.macros().all()
                assertEquals(listOf("older", "newer", "image"), rows.map { it.id })
                assertTrue(rows[0].selected); assertFalse(rows[1].selected)
                assertEquals(1700, rows[0].interval)
                db.macros().deleteAndCompact("newer")
                assertEquals(listOf("older", "image"), db.macros().all().map { it.id })
                assertEquals(listOf(0, 1), db.macros().all().map { it.priority })
            }
        } finally { context.deleteDatabase(name) }
    }
}
