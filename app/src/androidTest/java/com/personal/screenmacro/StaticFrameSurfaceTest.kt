package com.personal.screenmacro

import android.app.Presentation
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Gravity
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A synthetic static display must produce an image after its buffer queue is replaced. */
class StaticFrameSurfaceTest {
    @Test fun unchangedDisplayComposesANewFrameIntoReplacementSurface() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val worker=HandlerThread("static-capture-test").apply { start() }
        val handler=Handler(worker.looper)
        val first=ImageReader.newInstance(160,160,PixelFormat.RGBA_8888,2)
        val replacement=ImageReader.newInstance(160,160,PixelFormat.RGBA_8888,2)
        val firstFrame=CountDownLatch(1); val replacementFrame=CountDownLatch(1)
        var display: VirtualDisplay?=null; var presentation: Presentation?=null
        fun listen(reader: ImageReader,latch: CountDownLatch) {
            reader.setOnImageAvailableListener({ source -> source.acquireLatestImage()?.use { image ->
                assertEquals(160,image.width); assertEquals(160,image.height)
                latch.countDown()
            } },handler)
        }
        try {
            listen(first,firstFrame); listen(replacement,replacementFrame)
            instrumentation.runOnMainSync {
                display=context.getSystemService(DisplayManager::class.java).createVirtualDisplay("StaticFrameTest",160,160,160,
                    first.surface,DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION)
                presentation=Presentation(context,display!!.display).apply {
                    setContentView(TextView(context).apply { text="STATIC"; setTextColor(Color.WHITE); setBackgroundColor(Color.BLUE); gravity=Gravity.CENTER })
                    show()
                }
            }
            assertTrue("initial synthetic frame missing",firstFrame.await(5,TimeUnit.SECONDS))
            SystemClock.sleep(200)
            instrumentation.runOnMainSync { display!!.surface=replacement.surface }
            assertTrue("unchanged display did not render into new surface",replacementFrame.await(5,TimeUnit.SECONDS))
        } finally {
            instrumentation.runOnMainSync { presentation?.dismiss(); display?.release() }
            val closed=CountDownLatch(1)
            handler.post { first.close(); replacement.close(); worker.quitSafely(); closed.countDown() }
            assertTrue(closed.await(3,TimeUnit.SECONDS))
        }
    }
}
