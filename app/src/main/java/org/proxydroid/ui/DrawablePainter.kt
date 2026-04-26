package org.proxydroid.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** Converts an Android Drawable into an ImageBitmap for use in Compose Image painters. */
fun Drawable.toImageBitmap(width: Int = intrinsicWidth.coerceAtLeast(1),
                           height: Int = intrinsicHeight.coerceAtLeast(1)): ImageBitmap {
    if (this is BitmapDrawable && bitmap != null) {
        return bitmap.asImageBitmap()
    }
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val saved = bounds
    setBounds(0, 0, width, height)
    draw(canvas)
    bounds = saved
    return bmp.asImageBitmap()
}
