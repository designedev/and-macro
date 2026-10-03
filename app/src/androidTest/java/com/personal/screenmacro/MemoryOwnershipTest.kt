package com.personal.screenmacro

import android.graphics.*
import android.os.Debug
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.personal.screenmacro.core.*
import com.personal.screenmacro.data.MacroRepository
import com.personal.screenmacro.recognition.Matchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MemoryOwnershipTest {
    @Test fun boundedLogSnapshotsRemainIndependent() {
        val previous = RuntimeStore.logs.value
        try {
            RuntimeStore.logs.value = emptyList()
            RuntimeStore.log(null, "FIRST")
            val first = RuntimeStore.logs.value
            repeat(1005) { RuntimeStore.log(null, "ENTRY_$it") }
            assertEquals(listOf("FIRST"), first.map { it.result })
            assertEquals(1000, RuntimeStore.logs.value.size)
            assertEquals("ENTRY_5", RuntimeStore.logs.value.first().result)
            assertEquals("ENTRY_1004", RuntimeStore.logs.value.last().result)
        } finally { RuntimeStore.logs.value = previous }
    }
    @Test fun repeatedFullHdImageRecognitionKeepsResultsAndReleasesFrames() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = MacroRepository(context) { false }
        val target = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply { color = Color.BLACK }
        Canvas(target).apply {
            drawColor(Color.WHITE); drawRect(5f, 5f, 30f, 50f, paint)
            paint.color = Color.RED; drawCircle(55f, 30f, 16f, paint)
        }
        val path = repository.saveTemplate(target)
        val macro = Macro(type = RecognitionType.IMAGE, templatePath = path, referenceWidth = 1920, referenceHeight = 1080)
        val matcher = Matchers(repository)
        fun sample(cycles: Int) {
            Runtime.getRuntime().gc()
            Log.i("MacroMemoryTest", "cycles=$cycles nativeAllocated=${Debug.getNativeHeapAllocatedSize()} javaUsed=${Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()}")
        }
        try {
            repeat(40) { iteration ->
                val frame = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
                try {
                    Canvas(frame).apply { drawColor(Color.rgb(90, 110, 130)); drawBitmap(target, 700f, 500f, null) }
                    val match = matcher.find(frame, macro, emptyList()) as MatchResult.Unique
                    assertEquals(740f, match.box.centerX, .5f)
                    assertEquals(530f, match.box.centerY, .5f)
                } finally { frame.recycle() }
                if ((iteration + 1) % 10 == 0) sample(iteration + 1)
            }
        } finally { matcher.close(); target.recycle(); repository.discard(path) }
        // Heap allocator caches and ML/runtime state prevent a reliable hard byte
        // assertion; samples are diagnostic evidence, not proof of no long-run leak.
    }
}
