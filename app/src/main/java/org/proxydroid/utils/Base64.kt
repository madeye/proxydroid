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

/**
 * Wrapper for Android's Base64 utilities.
 * This provides a consistent API for Base64 encoding/decoding.
 */
object Base64 {
    const val NO_WRAP = android.util.Base64.NO_WRAP
    const val DEFAULT = android.util.Base64.DEFAULT
    const val URL_SAFE = android.util.Base64.URL_SAFE
    const val NO_PADDING = android.util.Base64.NO_PADDING

    @JvmStatic
    fun encode(input: ByteArray, flags: Int): ByteArray {
        return android.util.Base64.encode(input, flags)
    }

    @JvmStatic
    fun encodeToString(input: ByteArray, flags: Int): String {
        return android.util.Base64.encodeToString(input, flags)
    }

    @JvmStatic
    fun decode(str: String, flags: Int): ByteArray {
        return android.util.Base64.decode(str, flags)
    }

    @JvmStatic
    fun decode(input: ByteArray, flags: Int): ByteArray {
        return android.util.Base64.decode(input, flags)
    }
}
