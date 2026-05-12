/* proxydroid - Global / Individual Proxy App for Android
 * Copyright (C) 2011 Max Lv <max.c.lv@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.proxydroid.utils

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

object Utils {
    private const val TAG = "ProxyDroid"

    @Volatile
    private var working = false

    @Volatile
    private var connecting = false

    @JvmStatic
    fun isWorking(): Boolean = working

    @JvmStatic
    fun setWorking(working: Boolean) {
        this.working = working
    }

    @JvmStatic
    fun isConnecting(): Boolean = connecting

    @JvmStatic
    fun setConnecting(connecting: Boolean) {
        this.connecting = connecting
    }

    @JvmStatic
    fun getDataPath(context: Context): String {
        return if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
            context.getExternalFilesDir(null)?.absolutePath ?: context.filesDir.absolutePath
        } else {
            context.filesDir.absolutePath
        }
    }

    @JvmStatic
    fun getAppIcon(context: Context, uid: Int): Drawable? {
        val pm = context.packageManager
        val packages = pm.getPackagesForUid(uid)
        if (packages != null && packages.isNotEmpty()) {
            try {
                val appInfo = pm.getApplicationInfo(packages[0], 0)
                return pm.getApplicationIcon(appInfo)
            } catch (e: PackageManager.NameNotFoundException) {
                Log.e(TAG, "Error getting app icon", e)
            }
        }
        return null
    }

    @JvmStatic
    fun copyAssets(context: Context, filename: String) {
        val assetManager = context.assets
        var input: InputStream? = null
        var output: OutputStream? = null

        try {
            input = assetManager.open(filename)
            val outFile = File(context.filesDir, filename)
            output = FileOutputStream(outFile)

            val buffer = ByteArray(1024)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                output.write(buffer, 0, read)
            }

            outFile.setExecutable(true, false)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to copy asset file: $filename", e)
        } finally {
            try {
                input?.close()
                output?.close()
            } catch (e: IOException) {
                // Ignore
            }
        }
    }
}
