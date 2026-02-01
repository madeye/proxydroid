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

package org.proxydroid

import java.io.FileDescriptor

object Exec {
    init {
        System.loadLibrary("exec")
    }

    @JvmStatic
    external fun close(fd: FileDescriptor)

    @JvmStatic
    external fun createSubprocess(
        rdt: Int,
        cmd: String,
        args: Array<String>?,
        envVars: Array<String>?,
        scripts: String?,
        processId: IntArray
    ): FileDescriptor

    @JvmStatic
    external fun hangupProcessGroup(processId: Int)

    @JvmStatic
    external fun waitFor(processId: Int): Int
}
