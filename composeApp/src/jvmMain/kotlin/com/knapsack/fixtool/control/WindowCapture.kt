package com.knapsack.fixtool.control

import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.toBufferedImage
import java.awt.Container
import java.awt.Robot
import java.awt.Window
import java.awt.image.BufferedImage

/**
 * **A window's pixels, read from the frame the window drew rather than from the screen.**
 *
 * `/screenshot` used to ask `Robot` for the part of the screen the window covers, and on macOS that is the screen,
 * not the window. Behind another app it photographs the other app. In a process without Screen Recording
 * permission it photographs the desktop wallpaper with every window left out, and the route still answered 200,
 * so an agent verifying a change was handed a picture of a lake and nothing saying why.
 *
 * Compose draws a window into a Skia layer that keeps the last frame it drew. Reading that frame needs no
 * permission and does not care what is in front. The screen is the fallback, for a window with no layer or a layer
 * that has not drawn yet.
 */
internal object WindowCapture {
    /** Call on the event dispatch thread: the layer and the bounds are Swing state. */
    fun capture(window: Window): BufferedImage = drawn(window) ?: Robot().createScreenCapture(window.bounds)

    // A layer that cannot give its frame is a reason to read the screen, so the exception is the fallback's cue.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun drawn(window: Window): BufferedImage? =
        layerIn(window)?.let { layer ->
            try {
                layer.screenshot()?.toBufferedImage()?.let(::argb)
            } catch (e: Exception) {
                null
            }
        }

    /** Skia's raster in a layout the PNG writer takes: it throws on the one `toBufferedImage` hands over. */
    private fun argb(image: BufferedImage): BufferedImage =
        BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB).also { copy ->
            copy.createGraphics().apply {
                drawImage(image, 0, 0, null)
                dispose()
            }
        }

    private fun layerIn(container: Container): SkiaLayer? =
        container.components.firstNotNullOfOrNull { component ->
            component as? SkiaLayer ?: (component as? Container)?.let(::layerIn)
        }
}
