package com.btr.proxy.selector.pac

class ProxyEvaluationException : ProxyException {
    constructor() : super()
    constructor(message: String?, cause: Throwable?) : super(message, cause)
    constructor(message: String?) : super(message)
    constructor(cause: Throwable?) : super(cause)

    companion object {
        private const val serialVersionUID = 1L
    }
}
