package com.personal.screenmacro

import android.graphics.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.personal.screenmacro.core.*
import com.personal.screenmacro.data.MacroRepository
import com.personal.screenmacro.recognition.Matchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VisionTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun openCvUniqueDuplicateExcludedAndConstantTemplate() = runBlocking {
        val repository = MacroRepository(context) { false }
        val target = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply { color = Color.BLACK }
        Canvas(target).apply {
            drawColor(Color.WHITE)
            drawRect(5f, 6f, 35f, 50f, paint)
            paint.color = Color.RED; drawCircle(58f, 30f, 16f, paint)
            paint.color = Color.BLUE; drawRect(45f, 5f, 70f, 12f, paint)
        }
        Matchers.validateTemplate(target)
        val path = repository.saveTemplate(target)
        val macro = Macro(type = RecognitionType.IMAGE, templatePath = path, referenceWidth = 500, referenceHeight = 300)
        val screen = Bitmap.createBitmap(500, 300, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(screen)
        canvas.drawColor(Color.rgb(90, 110, 130)); canvas.drawBitmap(target, 100f, 120f, null)
        val matcher = Matchers(repository)
        try {
            val one = matcher.find(screen, macro, emptyList()) as MatchResult.Unique
            assertEquals(140f, one.box.centerX, .5f); assertEquals(150f, one.box.centerY, .5f)
            assertEquals(MatchResult.Absent, matcher.find(screen, macro, listOf(Box(90f, 100f, 190f, 190f))))
            canvas.drawBitmap(target, 300f, 120f, null)
            assertTrue(matcher.find(screen, macro, emptyList()) is MatchResult.Ambiguous)
            val constant = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            try { assertTrue(runCatching { Matchers.validateTemplate(constant) }.isFailure) } finally { constant.recycle() }
        } finally { matcher.close(); target.recycle(); screen.recycle(); repository.discard(path) }
    }
    @Test fun bundledKoreanAndEnglishModelsRecognizeSyntheticLines() = runBlocking {
        val repository = MacroRepository(context) { false }
        val bitmap = Bitmap.createBitmap(1500, 480, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 72f; typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
        canvas.drawText("한국어 확인", 60f, 110f, paint)
        canvas.drawText("Confirm OK", 60f, 250f, paint)
        canvas.drawText("확인 Confirm", 60f, 390f, paint)
        val matcher = Matchers(repository)
        try {
            assertTrue(matcher.find(bitmap, Macro(query = "한국어 확인"), emptyList()) is MatchResult.Unique)
            assertTrue(matcher.find(bitmap, Macro(query = "Confirm OK"), emptyList()) is MatchResult.Unique)
            assertTrue(matcher.find(bitmap, Macro(query = "확인 Confirm"), emptyList()) is MatchResult.Unique)
            assertEquals(MatchResult.Absent, matcher.find(bitmap, Macro(query = "없는 문구"), emptyList()))
        } finally { matcher.close(); bitmap.recycle() }
    }
    @Test fun entryButtonTextAndDiagnosticLinesRespectExclusions() = runBlocking {
        val bitmap = Bitmap.createBitmap(1080, 700, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 64f; typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
        Canvas(bitmap).apply { drawColor(Color.rgb(25, 40, 65)); drawText("입장하기", 200f, 300f, paint) }
        val matcher = Matchers(MacroRepository(context) { false })
        try {
            matcher.warmUpText()
            val found = matcher.findDetailed(bitmap, Macro(query = "입장하기"), emptyList())
            assertTrue(found.result is MatchResult.Unique)
            assertTrue(found.texts.any { normalizeText(it.text) == "입장하기" })
            val excluded = matcher.findDetailed(bitmap, Macro(query = "입장하기"), listOf(Box(150f, 200f, 600f, 350f)))
            assertEquals(MatchResult.Absent, excluded.result)
            assertTrue(excluded.texts.isEmpty())
        } finally { matcher.close(); bitmap.recycle() }
    }
    @Test fun severalTextRulesShareFrameAndRespectIndividualRegionsAndMatchModes() = runBlocking {
        val bitmap = Bitmap.createBitmap(1080, 700, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 64f; typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
        Canvas(bitmap).apply { drawColor(Color.WHITE); drawText("입장하기", 120f, 230f, paint); drawText("Confirm OK", 120f, 510f, paint) }
        val entry = Macro(id = "entry", query = "입장하기")
        val confirm = Macro(id = "confirm", query = "Confirm", matchMode = MatchMode.CONTAINS)
        val absent = Macro(id = "absent", query = "입장하기", region = SearchRegion(top = .5f))
        val matcher = Matchers(MacroRepository(context) { false })
        try {
            val found = matcher.findAll(bitmap, listOf(entry, confirm, absent), emptyList())
            assertTrue(found.getValue(entry.id).result is MatchResult.Unique)
            assertTrue(found.getValue(confirm.id).result is MatchResult.Unique)
            assertEquals(MatchResult.Absent, found.getValue(absent.id).result)
            val excluded = matcher.findAll(bitmap, listOf(entry, confirm), listOf(Box(50f, 120f, 800f, 260f)))
            assertEquals(MatchResult.Absent, excluded.getValue(entry.id).result)
            assertTrue(excluded.getValue(confirm.id).result is MatchResult.Unique)
        } finally { matcher.close(); bitmap.recycle() }
    }

    @Test fun imageAndTextRulesCanRecognizeTheSameFrame() = runBlocking {
        val repository = MacroRepository(context) { false }
        val bitmap = Bitmap.createBitmap(1080, 700, Bitmap.Config.ARGB_8888)
        val target = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 64f }
        Canvas(target).apply {
            drawColor(Color.WHITE); drawRect(4f, 4f, 30f, 50f, paint)
            paint.color = Color.RED; drawCircle(55f, 30f, 16f, paint)
        }
        paint.color = Color.BLACK
        Canvas(bitmap).apply { drawColor(Color.WHITE); drawText("입장하기", 100f, 230f, paint); drawBitmap(target, 780f, 480f, null) }
        val path = repository.saveTemplate(target)
        val text = Macro(id = "text", query = "입장하기")
        val image = Macro(id = "image", type = RecognitionType.IMAGE, templatePath = path, referenceWidth = 1080, referenceHeight = 700)
        val matcher = Matchers(repository)
        try {
            val found = matcher.findAll(bitmap, listOf(text, image), emptyList())
            assertTrue(found.getValue(text.id).result is MatchResult.Unique)
            val match = found.getValue(image.id).result as MatchResult.Unique
            assertEquals(820f, match.box.centerX, .5f)
            assertEquals(510f, match.box.centerY, .5f)
        } finally { matcher.close(); bitmap.recycle(); target.recycle(); repository.discard(path) }
    }

}
