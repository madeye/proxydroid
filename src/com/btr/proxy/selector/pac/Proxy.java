package com.btr.proxy.selector.pac;

public class Proxy {

	public final static Proxy NO_PROXY = new Proxy(null, 0, null);

	public final static String TYPE_HTTP = "http";
	public final static String TYPE_SOCKS4 = "socks4";
	public final static String TYPE_SOCKS5 = "socks5";

	public String type = "http";
	public String host = "";
	public int port = 3128;

	public Proxy(String host, int port, String type) {
		this.host = host;
		this.port = port;
		this.type = type;
	}

}
