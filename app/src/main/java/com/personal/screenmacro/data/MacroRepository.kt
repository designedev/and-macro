package com.personal.screenmacro.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.personal.screenmacro.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

@Entity(tableName = "macros")
data class MacroRow(
    @PrimaryKey val id: String, val name: String, val type: String,
    val left: Float, val top: Float, val right: Float, val bottom: Float,
    val interval: Long, val pre: Long, val post: Long, val query: String,
    val mode: String, val ignoreCase: Boolean, val template: String?, val threshold: Double,
    val width: Int, val height: Int, val rotation: Int, val created: Long, val updated: Long,
    // Kept only for version-1 database compatibility; never used to select or restrict an app.
    val targetPackage: String = "",
    @ColumnInfo(defaultValue = "0") val priority: Int = Int.MAX_VALUE,
    @ColumnInfo(defaultValue = "0") val selected: Boolean = false
) {
    fun model() = Macro(id, name, RecognitionType.valueOf(type), SearchRegion(left, top, right, bottom), interval, pre, post,
        query, MatchMode.valueOf(mode), ignoreCase, template, threshold, width, height, rotation, created, updated, priority, selected)
    companion object { fun from(m: Macro) = MacroRow(m.id, m.name, m.type.name, m.region.left, m.region.top, m.region.right, m.region.bottom,
        m.intervalMs, m.preDelayMs, m.postDelayMs, m.query, m.matchMode.name, m.ignoreCase, m.templatePath, m.threshold,
        m.referenceWidth, m.referenceHeight, m.referenceRotation, m.createdAt, m.updatedAt, "", m.priority, m.selected) }
}
@Dao interface MacroDao {
    @Query("SELECT * FROM macros ORDER BY priority ASC, created ASC, id ASC") fun watch(): Flow<List<MacroRow>>
    @Query("SELECT * FROM macros ORDER BY priority ASC, created ASC, id ASC") suspend fun all(): List<MacroRow>
    @Query("SELECT * FROM macros WHERE id = :id") suspend fun get(id: String): MacroRow?
    @Upsert suspend fun put(row: MacroRow)
    @Query("DELETE FROM macros WHERE id = :id") suspend fun delete(id: String)
    @Transaction suspend fun deleteAndCompact(id: String) {
        delete(id)
        all().forEachIndexed { index, row -> rank(row.id, index) }
    }
    @Query("UPDATE macros SET priority = :priority WHERE id = :id") suspend fun rank(id: String, priority: Int)
    @Query("UPDATE macros SET selected = :selected WHERE id = :id") suspend fun select(id: String, selected: Boolean)
    @Transaction suspend fun reorder(ids: List<String>) {
        require(ids.size == ids.toSet().size && ids.toSet() == all().map { it.id }.toSet()) { "목록이 변경되었습니다. 다시 순서를 조절하세요." }
        ids.forEachIndexed { index, id -> rank(id, index) }
    }
}
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE macros ADD COLUMN priority INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE macros ADD COLUMN selected INTEGER NOT NULL DEFAULT 0")
        // Preserve the old newest-first display order when introducing explicit ranks.
        db.execSQL("UPDATE macros SET priority = (SELECT COUNT(*) FROM macros AS other WHERE other.updated > macros.updated OR (other.updated = macros.updated AND other.id < macros.id))")
    }
}
@Database(entities = [MacroRow::class], version = 2, exportSchema = false)
abstract class MacroDatabase : RoomDatabase() { abstract fun macros(): MacroDao }
class MacroRepository(context: Context, private val busy: () -> Boolean) {
    private val templates = File(context.filesDir, "templates").apply { mkdirs() }
    private val database = Room.databaseBuilder(context, MacroDatabase::class.java, "macros.db").addMigrations(MIGRATION_1_2).build()
    private val dao = database.macros()
    private val mutations = Mutex()
    val macros: Flow<List<Macro>> = dao.watch().map { rows -> rows.mapNotNull { runCatching { it.model() }.getOrNull() } }
    fun file(path: String): File {
        require(path.matches(Regex("[a-f0-9-]+\\.png"))) { "잘못된 기준 이미지 경로" }
        return File(templates, path)
    }
    fun exists(path: String) = runCatching { file(path).isFile }.getOrDefault(false)
    suspend fun get(id: String) = dao.get(id)?.model()?.also { it.validate(::exists) }
    suspend fun getOrdered(ids: List<String>): List<Macro> {
        require(ids.isNotEmpty() && ids.size == ids.toSet().size) { "실행할 매크로를 선택하세요." }
        val rows = dao.all().filter { it.id in ids }
        check(rows.size == ids.size) { "선택한 매크로가 변경되었습니다. 다시 선택하세요." }
        return rows.map { it.model().also { m -> m.validate(::exists) } }
    }
    suspend fun setSelected(id: String, selected: Boolean) = mutations.withLock {
        check(!busy()) { "실행을 정지한 뒤 선택을 변경하세요." }
        check(dao.get(id) != null) { "매크로가 없습니다." }
        dao.select(id, selected)
    }
    suspend fun reorder(ids: List<String>) = mutations.withLock {
        check(!busy()) { "실행을 정지한 뒤 순서를 변경하세요." }
        dao.reorder(ids)
    }
    suspend fun save(m: Macro) = mutations.withLock {
        check(!busy()) { "실행을 정지한 뒤 수정하세요." }
        m.validate(::exists)
        val previous = dao.get(m.id)
        val priority = previous?.priority ?: ((dao.all().maxOfOrNull { it.priority } ?: -1) + 1)
        dao.put(MacroRow.from(m.copy(updatedAt = System.currentTimeMillis(), priority = priority, selected = previous?.selected ?: m.selected)))
        if (previous?.template != null && previous.template != m.templatePath) file(previous.template).delete()
    }
    suspend fun delete(m: Macro) = mutations.withLock {
        check(!busy()) { "실행을 정지한 뒤 삭제하세요." }
        dao.deleteAndCompact(m.id)
        m.templatePath?.let { file(it).delete() }
    }
    suspend fun saveTemplate(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        val path = "${UUID.randomUUID()}.png"
        file(path).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        path
    }
    suspend fun bitmap(path: String): Bitmap {
        val ownership = ResourceHandoff<Bitmap> { it.recycle() }
        try {
            withContext(Dispatchers.IO) {
                val bitmap = BitmapFactory.decodeFile(file(path).absolutePath) ?: error("기준 이미지를 읽을 수 없습니다.")
                ownership.offer(bitmap)
            }
            return ownership.take()
        } finally { ownership.close() }
    }
    fun discard(path: String) { file(path).delete() }
}
