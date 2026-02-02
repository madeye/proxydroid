package com.btr.proxy.selector.pac

import java.io.IOException

interface PacScriptSource {
    @Throws(IOException::class)
    fun getScriptContent(): String
}
