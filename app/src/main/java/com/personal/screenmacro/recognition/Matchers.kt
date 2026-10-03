package com.personal.screenmacro.recognition

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.personal.screenmacro.core.*
import com.personal.screenmacro.data.MacroRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.max

data class RecognitionOutcome(val result: MatchResult, val texts: List<TextCandidate> = emptyList())

class Matchers(private val repository: MacroRepository) : AutoCloseable {
    private val korean by lazy { TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()) }
    private val latin by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private var ocrUsed = false
    suspend fun find(bitmap: Bitmap, macro: Macro, excluded: List<Box>): MatchResult = findDetailed(bitmap, macro, excluded).result
    suspend fun warmUpText() {
        val sample = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
        try { find(sample, Macro(query = "준비"), emptyList()) } finally { sample.recycle() }
    }
    suspend fun findDetailed(bitmap: Bitmap, macro: Macro, excluded: List<Box>): RecognitionOutcome = withContext(Dispatchers.Default) {
        val region = macro.region.pixels(bitmap.width, bitmap.height)
        require(region.width >= 1 && region.height >= 1) { "검색 영역이 너무 작습니다." }
        val crop = Bitmap.createBitmap(bitmap, region.left.toInt(), region.top.toInt(), region.width.toInt(), region.height.toInt())
        try {
            if (macro.type == RecognitionType.IMAGE) RecognitionOutcome(image(crop, region, macro, excluded))
            else textOutcome(readText(crop, region, excluded), macro)
        } finally { if (crop !== bitmap) crop.recycle() }
    }
    suspend fun findAll(bitmap: Bitmap, macros: List<Macro>, excluded: List<Box>): Map<String, RecognitionOutcome> = withContext(Dispatchers.Default) {
        val textCache = mutableMapOf<SearchRegion, List<TextCandidate>>()
        val outcomes = linkedMapOf<String, RecognitionOutcome>()
        for (macro in macros) {
            currentCoroutineContext().ensureActive()
            if (macro.type == RecognitionType.IMAGE) {
                outcomes[macro.id] = findDetailed(bitmap, macro, excluded)
            } else {
                val lines = textCache[macro.region] ?: run {
                    val region = macro.region.pixels(bitmap.width, bitmap.height)
                    require(region.width >= 1 && region.height >= 1) { "검색 영역이 너무 작습니다." }
                    val crop = Bitmap.createBitmap(bitmap, region.left.toInt(), region.top.toInt(), region.width.toInt(), region.height.toInt())
                    try { readText(crop, region, excluded) } finally { if (crop !== bitmap) crop.recycle() }
                }.also { textCache[macro.region] = it }
                outcomes[macro.id] = textOutcome(lines, macro)
            }
        }
        outcomes
    }
    private suspend fun image(crop: Bitmap, region: Box, macro: Macro, excluded: List<Box>): MatchResult {
        check(OpenCVLoader.initLocal()) { "OpenCV 초기화 실패" }
        val template = repository.bitmap(macro.templatePath ?: error("기준 이미지 없음"))
        val screen = Mat(); val target = Mat(); val scores = Mat()
        try {
            validateTemplate(template)
            require(template.width <= crop.width && template.height <= crop.height) { "기준 이미지가 검색 영역보다 큽니다." }
            Utils.bitmapToMat(crop, screen); Utils.bitmapToMat(template, target)
            Imgproc.cvtColor(screen, screen, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.cvtColor(target, target, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.matchTemplate(screen, target, scores, Imgproc.TM_CCOEFF_NORMED)
            val found = mutableListOf<Box>()
            repeat(256) {
                currentCoroutineContext().ensureActive()
                val peak = Core.minMaxLoc(scores)
                if (!peak.maxVal.isFinite() || peak.maxVal < macro.threshold) return when (found.size) {
                    0 -> MatchResult.Absent
                    1 -> MatchResult.Unique(found.first())
                    else -> MatchResult.Ambiguous(found.size)
                }
                val x = peak.maxLoc.x; val y = peak.maxLoc.y
                val box = Box((x + region.left).toFloat(), (y + region.top).toFloat(),
                    (x + region.left + template.width).toFloat(), (y + region.top + template.height).toFloat())
                if (excluded.none { it.intersects(box) } && found.none { it.iou(box) > 0.25f }) {
                    found += box
                    if (found.size > 1) return MatchResult.Ambiguous(found.size)
                }
                // Suppress overlapping peaks belonging to the same physical element.
                Imgproc.rectangle(scores,
                    Point(max(0.0, x - template.width / 2.0), max(0.0, y - template.height / 2.0)),
                    Point(minOf(scores.cols() - 1.0, x + template.width / 2.0), minOf(scores.rows() - 1.0, y + template.height / 2.0)), Scalar(-1.0), -1)
            }
            return MatchResult.Ambiguous(256)
        } finally { screen.release(); target.release(); scores.release(); template.recycle() }
    }
    private suspend fun readText(crop: Bitmap, region: Box, excluded: List<Box>): List<TextCandidate> {
        ocrUsed = true
        val input = InputImage.fromBitmap(crop, 0)
        // ML Kit tasks may outlive cancellation. Keep the bitmap alive until both tasks finish.
        val results = withContext(NonCancellable) {
            val k = korean.process(input)
            val l = latin.process(input)
            val kr = runCatching { k.await() }
            val lr = runCatching { l.await() }
            listOf(kr.getOrThrow(), lr.getOrThrow())
        }
        currentCoroutineContext().ensureActive()
        val lines = results.flatMap { result -> result.textBlocks.flatMap { block -> block.lines.mapNotNull { line ->
            line.boundingBox?.let { r -> TextCandidate(line.text, Box(r.left + region.left, r.top + region.top, r.right + region.left, r.bottom + region.top)) }
        } } }
        return mergeTextCandidates(lines.filter { it.box.valid() && excluded.none { b -> b.intersects(it.box) } })
    }
    private fun textOutcome(visible: List<TextCandidate>, macro: Macro): RecognitionOutcome {
        val candidates = visible.filter { textMatches(it.text, macro) }
        return RecognitionOutcome(when (candidates.size) { 0 -> MatchResult.Absent; 1 -> MatchResult.Unique(candidates.first().box); else -> MatchResult.Ambiguous(candidates.size) }, visible)
    }
    override fun close() { if (ocrUsed) { korean.close(); latin.close() } }
    companion object {
        fun validateTemplate(bitmap: Bitmap) {
            require(bitmap.width >= 12 && bitmap.height >= 12) { "기준 이미지는 가로·세로 12px 이상이어야 합니다." }
            check(OpenCVLoader.initLocal()) { "OpenCV 초기화 실패" }
            val mat = Mat(); val mean = MatOfDouble(); val deviation = MatOfDouble()
            try {
                Utils.bitmapToMat(bitmap, mat)
                Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2GRAY)
                Core.meanStdDev(mat, mean, deviation)
                require(deviation.toArray()[0] >= 5.0) { "단색 등 특징이 부족한 이미지입니다. 글자나 윤곽이 포함되도록 자르세요." }
            } finally { mat.release(); mean.release(); deviation.release() }
        }
    }
}
