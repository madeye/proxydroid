package com.btr.proxy.selector.pac

interface PacScriptParser {
    fun getScriptSource(): PacScriptSource

    @Throws(ProxyEvaluationException::class)
    fun evaluate(url: String, host: String): String
}
