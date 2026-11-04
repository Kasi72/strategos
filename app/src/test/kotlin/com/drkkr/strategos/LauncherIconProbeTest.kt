package com.drkkr.strategos

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.content.res.ResourcesCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
/** The launcher icon must inflate and draw more than its background (a blank icon or the default robot means the logo is missing). */
class LauncherIconProbeTest {
    @Test fun launcherIconInflatesAndRenders() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val d = ctx.resources.getDrawable(R.mipmap.ic_launcher, ctx.theme)
        println("ICON class=${d.javaClass.simpleName} intrinsic=${d.intrinsicWidth}x${d.intrinsicHeight}")
        val bmp = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, 432, 432); d.draw(Canvas(bmp))
        val out = System.getenv("ICON_OUT")
        if (out != null) File(out).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertNotNull("monochrome layer for themed icons", (d as android.graphics.drawable.AdaptiveIconDrawable).monochrome)
        var nonBg = 0
        val bg = bmp.getPixel(2, 2)
        for (x in 0 until 432 step 4) for (y in 0 until 432 step 4) if (bmp.getPixel(x, y) != bg) nonBg++
        println("ICON pixels differing from background: $nonBg of ${108 * 108}")
        assertTrue("icon renders only its background", nonBg > 50)
    }
}
