package com.btr.proxy.selector.pac;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import android.util.Log;

/*****************************************************************************
 * ProxySelector that will use a PAC script to find an proxy for a given URI.
 * 
 * @author Bernd Rosstauscher (proxyvole@rosstauscher.de) Copyright 2009
 ****************************************************************************/
public class PacProxySelector {

	// private static final String PAC_PROXY = "PROXY";
	private static final String PAC_SOCKS = "SOCKS";
	private static final String PAC_DIRECT = "DIRECT";

	private final static String TAG = "ProxyDroid.PAC";

	private PacScriptParser pacScriptParser;

	/*************************************************************************
	 * Constructor
	 * 
	 * @param pacSource
	 *            the source for the PAC file.
	 ************************************************************************/

	public PacProxySelector(PacScriptSource pacSource) {
		super();
		selectEngine(pacSource);
	}

	/*************************************************************************
	 * Selects one of the available PAC parser engines.
	 * 
	 * @param pacSource
	 *            to use as input.
	 ************************************************************************/

	private void selectEngine(PacScriptSource pacSource) {
		try {
			this.pacScriptParser = new RhinoPacScriptParser(pacSource);
		} catch (Exception e) {
			Log.e(TAG, "PAC parser error.", e);
		}
	}

	/*************************************************************************
	 * select
	 * 
	 * @see java.net.ProxySelector#select(java.net.URI)
	 ************************************************************************/
	public List<Proxy> select(URI uri) {
		if (uri == null || uri.getHost() == null) {
			throw new IllegalArgumentException("URI must not be null.");
		}

		// Fix for Java 1.6.16 where we get a infinite loop because
		// URL.connect(Proxy.NO_PROXY) does not work as expected.
		PacScriptSource scriptSource = this.pacScriptParser.getScriptSource();
		if (String.valueOf(scriptSource).contains(uri.getHost())) {
			return null;
		}

		return findProxy(uri);
	}

	/*************************************************************************
	 * Evaluation of the given URL with the PAC-file.
	 * 
	 * Two cases can be handled here: DIRECT Fetch the object directly from the
	 * content HTTP server denoted by its URL PROXY name:port Fetch the object
	 * via the proxy HTTP server at the given location (name and port)
	 * 
	 * @param uri
	 *            <code>URI</code> to be evaluated.
	 * @return <code>Proxy</code>-object list as result of the evaluation.
	 ************************************************************************/

	private List<Proxy> findProxy(URI uri) {
		try {
			List<Proxy> proxies = new ArrayList<Proxy>();
			String parseResult = this.pacScriptParser.evaluate(uri.toString(),
					uri.getHost());
			String[] proxyDefinitions = parseResult.split("[;]");
			for (String proxyDef : proxyDefinitions) {
				if (proxyDef.trim().length() > 0) {
					proxies.add(buildProxyFromPacResult(proxyDef));
				}
			}
			return proxies;
		} catch (ProxyEvaluationException e) {
			Log.e(TAG, "PAC resolving error.", e);
			return null;
		}
	}

	/*************************************************************************
	 * The proxy evaluator will return a proxy string. This method will take
	 * this string and build a matching <code>Proxy</code> for it.
	 * 
	 * @param pacResult
	 *            the result from the PAC parser.
	 * @return a Proxy
	 ************************************************************************/

	private Proxy buildProxyFromPacResult(String pacResult) {
		if (pacResult == null || pacResult.trim().length() < 6) {
			return Proxy.NO_PROXY;
		}
		String proxyDef = pacResult.trim();
		if (proxyDef.toUpperCase().startsWith(PAC_DIRECT)) {
			return Proxy.NO_PROXY;
		}

		// Check proxy type.
		String type = Proxy.TYPE_HTTP;
		if (proxyDef.toUpperCase().startsWith(PAC_SOCKS)) {
			type = Proxy.TYPE_SOCKS5;
		}

		String host = proxyDef.substring(6);
		Integer port = 80;

		// Split port from host
		int indexOfPort = host.indexOf(':');
		if (indexOfPort != -1) {
			port = Integer.parseInt(host.substring(indexOfPort + 1).trim());
			host = host.substring(0, indexOfPort).trim();
		}

		return new Proxy(host, port, type);
	}
}
