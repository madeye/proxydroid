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
import org.proxydroid.Exec
import java.io.*

object Utils {
    private const val TAG = "ProxyDroid"

    @Volatile
    private var isRoot = false

    @Volatile
    private var working = false

    @Volatile
    private var connecting = false

    @JvmStatic
    fun isRoot(): Boolean = isRoot

    @JvmStatic
    fun setRoot(root: Boolean) {
        isRoot = root
    }

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
    fun runRootCommand(command: String): String {
        return runRootCommand(command, 10000)
    }

    @JvmStatic
    fun runRootCommand(command: String, timeout: Int): String {
        val result = StringBuilder()
        var process: Process? = null
        var output: DataOutputStream? = null
        var input: BufferedReader? = null

        try {
            process = Runtime.getRuntime().exec("su")
            output = DataOutputStream(process.outputStream)
            input = BufferedReader(InputStreamReader(process.inputStream))

            output.writeBytes("$command\n")
            output.writeBytes("exit\n")
            output.flush()

            var line: String?
            while (input.readLine().also { line = it } != null) {
                result.append(line).append("\n")
            }

            process.waitFor()

        } catch (e: Exception) {
            Log.e(TAG, "Error running root command", e)
        } finally {
            try {
                output?.close()
                input?.close()
                process?.destroy()
            } catch (e: Exception) {
                // Ignore
            }
        }

        return result.toString()
    }

    @JvmStatic
    fun runScript(
        context: Context,
        script: String,
        res: IntArray,
        returnOutput: Boolean
    ): String {
        val result = StringBuilder()
        val processId = IntArray(1)

        try {
            val fd = Exec.createSubprocess(
                0, "/system/bin/sh", arrayOf("-"), null, script, processId
            )

            if (processId[0] > 0) {
                val exitCode = Exec.waitFor(processId[0])
                res[0] = exitCode
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error running script", e)
            res[0] = -1
        }

        return result.toString()
    }

    @JvmStatic
    fun checkRoot(): Boolean {
        var rooted = false
        try {
            val process = Runtime.getRuntime().exec("su")
            val output = DataOutputStream(process.outputStream)
            output.writeBytes("id\n")
            output.writeBytes("exit\n")
            output.flush()

            val input = BufferedReader(InputStreamReader(process.inputStream))
            val line = input.readLine()
            if (line != null && line.contains("uid=0")) {
                rooted = true
            }

            process.waitFor()
            output.close()
            input.close()
            process.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking root", e)
        }
        isRoot = rooted
        return rooted
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

    @JvmStatic
    fun getHasRedirectSupport(): Boolean {
        val result = runRootCommand("iptables -t nat -L -n")
        return result.contains("REDIRECT")
    }

    @JvmStatic
    fun getIptablesPath(): String {
        return "iptables"
    }

    @JvmStatic
    fun getShellPath(): String {
        // Prefer /system/bin/sh
        if (File("/system/bin/sh").exists()) {
            return "/system/bin/sh"
        }
        return "/bin/sh"
    }
}
