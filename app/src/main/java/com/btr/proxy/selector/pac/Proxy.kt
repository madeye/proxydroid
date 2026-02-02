package com.btr.proxy.selector.pac

class Proxy(
    var host: String? = "",
    var port: Int = 3128,
    var type: String? = TYPE_HTTP
) {
    companion object {
        @JvmField
        val NO_PROXY = Proxy(null, 0, null)

        const val TYPE_HTTP = "http"
        const val TYPE_HTTPS = "https"
        const val TYPE_SOCKS4 = "socks4"
        const val TYPE_SOCKS5 = "socks5"
    }
}
