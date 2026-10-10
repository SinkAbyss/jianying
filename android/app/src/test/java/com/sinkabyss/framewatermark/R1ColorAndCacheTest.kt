package com.sinkabyss.framewatermark

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.Executors
import java.util.concurrent.Callable

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class R1ColorAndCacheTest {
    @Test fun aTinySaturatedAccentDoesNotReplaceTheSupportedBackgroundFamily() {
        val base = 0xffb5b0a5.toInt()
        val source = Bitmap.createBitmap(1000,600,Bitmap.Config.ARGB_8888).apply { eraseColor(base) }
        Canvas(source).drawRect(400f,400f,500f,500f,Paint().apply { color=0xff0055ff.toInt() })
        val analysis=PhotoColorAnalyzer.analyze(source)
        val style=BorderStyleResolver.resolve(BorderBackgroundMode.DARK_ADAPTIVE,analysis)
        assertTrue("Tiny blue accent must not choose the entire dark band", ColorScience.hueDelta(style.representative!!.hue,ColorScience.toOklch(base).hue)<30.0)
        source.recycle()
    }

    @Test fun concurrentColdPreparationSharesOneImmutableResultAndDifferentGeometryStaysSeparate() {
        FrameBackgroundRenderer.clearCache()
        val source=Bitmap.createBitmap(1000,600,Bitmap.Config.ARGB_8888).apply { eraseColor(0xffaaaaaa.toInt()) }
        val style=BorderStyleResolver.resolve(BorderBackgroundMode.BLUR,PhotoColorAnalyzer.analyze(source))
        val geometry=FrameGeometry.calculate(1000,600,FrameLayoutKind.BAR)
        val workers=Executors.newFixedThreadPool(2)
        try {
            val futures=workers.invokeAll(List(2) { Callable { FrameRenderer.prepareBackground(source,geometry,style) } })
            assertSame(futures[0].get(),futures[1].get())
            val box=FrameRenderer.prepareBackground(source,FrameGeometry.calculate(1000,600,FrameLayoutKind.BOX),style)
            assertTrue(box !== futures[0].get())
            assertTrue(!(futures[0].get() as MaterializedBackground.Photo).bitmap.isRecycled)
            assertSame(futures[0].get(),FrameRenderer.prepareBackground(source,geometry,style))
        } finally { workers.shutdown(); FrameBackgroundRenderer.clearCache(); source.recycle() }
    }
}
