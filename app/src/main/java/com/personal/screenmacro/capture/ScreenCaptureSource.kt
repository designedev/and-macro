package com.personal.screenmacro.capture

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.personal.screenmacro.core.WindowStamp
import com.personal.screenmacro.core.awaitFreshFrame
import com.personal.screenmacro.RuntimeStore
import kotlinx.coroutines.CompletableDeferred
import java.util.UUID

class Frame(val bitmap: Bitmap, val time: Long, val session: String, val rotation: Int, val window: WindowStamp)
class ScreenCaptureSource(
    private val projection: MediaProjection, val width: Int, val height: Int, val rotation: Int, density: Int,
    private val stopped: (String) -> Unit
) : AutoCloseable {
    val sessionId: String = UUID.randomUUID().toString()
    @Volatile var active = true; private set
    @Volatile var sizeConfirmed = false; private set
    private val thread = HandlerThread("screen-frame").apply { start() }
    private val worker = Handler(thread.looper)
    private val callbackHandler = Handler(android.os.Looper.getMainLooper())
    private var reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
    private var display: VirtualDisplay? = null
    private val lock = Any()
    private var request: Pair<CompletableDeferred<Frame>, WindowStamp>? = null
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() { stopped("화면 캡처가 종료되었습니다. 다시 동의하고 시작하세요.") }
        override fun onCapturedContentResize(w: Int, h: Int) {
            if (w != width || h != height) stopped("전체 화면 크기와 다릅니다. 앱 공유 또는 화면 변경으로 실행을 중단했습니다.")
            else sizeConfirmed = true
        }
        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            if (!isVisible) stopped("공유 화면이 보이지 않습니다. 수동으로 재시작하세요.")
        }
    }
    init {
        projection.registerCallback(callback, callbackHandler)
        listen(reader)
        try {
            display = projection.createVirtualDisplay("ScreenMacro", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, worker)
        } catch (e: Exception) { close(); throw e }
    }
    private fun listen(targetReader: ImageReader) {
        targetReader.setOnImageAvailableListener({ source ->
            if (source !== reader || !active) return@setOnImageAvailableListener
            val image = runCatching { source.acquireLatestImage() }.onFailure {
                RuntimeStore.log(null, "FRAME_ACQUIRE_ERROR", error = it.javaClass.simpleName)
            }.getOrNull() ?: return@setOnImageAvailableListener
            image.use {
                synchronized(lock) {
                    val pending = request ?: return@synchronized
                    request = null
                    if (!active || !pending.first.isActive) return@synchronized
                    try {
                        val plane = image.planes[0]
                        check(plane.pixelStride == 4) { "지원하지 않는 캡처 형식" }
                        val paddedWidth = plane.rowStride / plane.pixelStride
                        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
                        val bitmap = try {
                            padded.copyPixelsFromBuffer(plane.buffer)
                            Bitmap.createBitmap(padded, 0, 0, width, height)
                        } catch (e: Exception) { padded.recycle(); throw e }
                        if (bitmap !== padded) padded.recycle()
                        val frame = Frame(bitmap, SystemClock.elapsedRealtime(), sessionId, rotation, pending.second)
                        if (!pending.first.complete(frame)) bitmap.recycle()
                    } catch (e: Exception) { pending.first.completeExceptionally(e) }
                }
            }
        }, worker)
    }
    suspend fun fresh(window: WindowStamp): Frame {
        check(active && sizeConfirmed) { "전체 화면 캡처 좌표가 확정되지 않았습니다." }
        val deferred = CompletableDeferred<Frame>()
        worker.post {
            synchronized(lock) {
                if (!active || !deferred.isActive) { deferred.cancel(); return@synchronized }
                runCatching { reader.acquireLatestImage()?.close() }
                check(request == null) { "중복 프레임 요청" }
                request = deferred to window
            }
        }
        try { return awaitFreshFrame(deferred, refresh = { refreshSurface(deferred) }) }
        finally {
            deferred.cancel()
            synchronized(lock) { if (request?.first === deferred) request = null }
        }
    }
    private fun refreshSurface(deferred: CompletableDeferred<Frame>) {
        worker.post {
            synchronized(lock) {
                if (!active || !deferred.isActive || request?.first !== deferred) return@synchronized
                val previous = reader
                var replacement: ImageReader? = null
                try {
                    val next = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                    replacement = next
                    listen(next)
                    // Keep the existing consent/session/display, but bind a new buffer
                    // queue so even an unchanged screen is freshly composed into it.
                    checkNotNull(display).surface = next.surface
                    reader = next
                    runCatching { previous.setOnImageAvailableListener(null, null); previous.close() }
                    RuntimeStore.log(null, "FRAME_SURFACE_REFRESH")
                } catch (e: Exception) {
                    replacement?.close()
                    request = null; deferred.completeExceptionally(e)
                }
            }
        }
    }
    override fun close() {
        synchronized(lock) {
            if (!active) return
            active = false
            request?.first?.cancel(); request = null
        }
        projection.unregisterCallback(callback)
        display?.release(); display = null
        // Closing on the reader thread avoids racing a callback using an Image.
        worker.post { reader.setOnImageAvailableListener(null, null); reader.close(); thread.quitSafely() }
        projection.stop()
    }
}
