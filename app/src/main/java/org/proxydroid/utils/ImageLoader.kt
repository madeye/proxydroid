package org.proxydroid.utils

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.widget.ImageView
import org.proxydroid.R
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.util.*

class ImageLoader(context: Context) {
    private val cache = HashMap<Int, Bitmap>()
    private val cacheDir: File = context.cacheDir
    private val context: Context = context
    private val stubId = R.drawable.sym_def_app_icon
    private val photosQueue = PhotosQueue()
    private val photoLoaderThread = PhotosLoader()

    init {
        photoLoaderThread.priority = Thread.NORM_PRIORITY - 1
    }

    fun clearCache() {
        cache.clear()
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    private fun decodeFile(f: File): Bitmap? {
        return try {
            val o = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeStream(FileInputStream(f), null, o)

            val requiredSize = 70
            var widthTmp = o.outWidth
            var heightTmp = o.outHeight
            var scale = 1
            while (true) {
                if (widthTmp / 2 < requiredSize || heightTmp / 2 < requiredSize) break
                widthTmp /= 2
                heightTmp /= 2
                scale *= 2
            }

            val o2 = BitmapFactory.Options().apply {
                inSampleSize = scale
            }
            BitmapFactory.decodeStream(FileInputStream(f), null, o2)
        } catch (e: FileNotFoundException) {
            null
        }
    }

    fun displayImage(uid: Int, activity: Activity, imageView: ImageView) {
        if (cache.containsKey(uid)) {
            imageView.setImageBitmap(cache[uid])
        } else {
            queuePhoto(uid, activity, imageView)
            imageView.setImageResource(stubId)
        }
    }

    private fun getBitmap(uid: Int): Bitmap? {
        val filename = uid.toString()
        val f = File(cacheDir, filename)

        // from SD cache
        val b = decodeFile(f)
        if (b != null) return b

        // from app
        return try {
            val icon = Utils.getAppIcon(context, uid) as? BitmapDrawable
            icon?.bitmap
        } catch (ex: Exception) {
            null
        }
    }

    private fun queuePhoto(uid: Int, activity: Activity, imageView: ImageView) {
        photosQueue.clean(imageView)
        val p = PhotoToLoad(uid, imageView)
        synchronized(photosQueue.lock) {
            photosQueue.photosToLoad.push(p)
            photosQueue.lock.notifyAll()
        }

        if (photoLoaderThread.state == Thread.State.NEW) {
            photoLoaderThread.start()
        }
    }

    fun stopThread() {
        photoLoaderThread.interrupt()
    }

    private inner class BitmapDisplayer(
        private val bitmap: Bitmap?,
        private val imageView: ImageView
    ) : Runnable {
        override fun run() {
            if (bitmap != null) {
                imageView.setImageBitmap(bitmap)
            } else {
                imageView.setImageResource(stubId)
            }
        }
    }

    private inner class PhotosLoader : Thread() {
        override fun run() {
            try {
                while (true) {
                    if (photosQueue.photosToLoad.isEmpty()) {
                        synchronized(photosQueue.lock) {
                            (photosQueue.lock as Object).wait()
                        }
                    }

                    synchronized(photosQueue.lock) {
                        if (photosQueue.photosToLoad.isNotEmpty()) {
                            val photoToLoad = photosQueue.photosToLoad.pop()
                            val bmp = getBitmap(photoToLoad.uid)
                            if (bmp != null) {
                                cache[photoToLoad.uid] = bmp
                            }
                            val tag = photoToLoad.imageView.tag
                            if (tag != null && tag as Int == photoToLoad.uid) {
                                val bd = BitmapDisplayer(bmp, photoToLoad.imageView)
                                val a = photoToLoad.imageView.context as Activity
                                a.runOnUiThread(bd)
                            }
                        }
                    }

                    if (Thread.interrupted()) break
                }
            } catch (e: InterruptedException) {
                // allow thread to exit
            }
        }
    }

    private inner class PhotosQueue {
        val photosToLoad = Stack<PhotoToLoad>()
        val lock = Any()

        fun clean(image: ImageView) {
            synchronized(lock) {
                try {
                    val iterator = photosToLoad.iterator()
                    while (iterator.hasNext()) {
                        if (iterator.next().imageView == image) {
                            iterator.remove()
                        }
                    }
                } catch (e: ArrayIndexOutOfBoundsException) {
                    // Nothing
                }
            }
        }
    }

    private data class PhotoToLoad(val uid: Int, val imageView: ImageView)
}
