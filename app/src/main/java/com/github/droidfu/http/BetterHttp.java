package com.github.droidfu.http;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.zip.GZIPInputStream;

import org.apache.http.Header;
import org.apache.http.HeaderElement;
import org.apache.http.HttpEntity;
import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.HttpResponse;
import org.apache.http.HttpResponseInterceptor;
import org.apache.http.HttpVersion;
import org.apache.http.conn.params.ConnManagerParams;
import org.apache.http.conn.params.ConnPerRouteBean;
import org.apache.http.conn.scheme.PlainSocketFactory;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.entity.HttpEntityWrapper;
import org.apache.http.impl.client.AbstractHttpClient;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpProtocolParams;
import org.apache.http.protocol.HttpContext;

public class BetterHttp {

	static final String LOG_TAG = "BetterHttp";

	public static final int DEFAULT_MAX_CONNECTIONS = 4;
	public static final int DEFAULT_SOCKET_TIMEOUT = 30 * 1000;
	public static final String DEFAULT_HTTP_USER_AGENT = "Android/DroidFu";
	private static final String HEADER_ACCEPT_ENCODING = "Accept-Encoding";
	private static final String ENCODING_GZIP = "gzip";

	private static int maxConnections = DEFAULT_MAX_CONNECTIONS;
	private static int socketTimeout = DEFAULT_SOCKET_TIMEOUT;
	private static String httpUserAgent = DEFAULT_HTTP_USER_AGENT;

	private static HashMap<String, String> defaultHeaders = new HashMap<String, String>();
	private static AbstractHttpClient httpClient;

	public static void setupHttpClient() {
		BasicHttpParams httpParams = new BasicHttpParams();

		ConnManagerParams.setTimeout(httpParams, socketTimeout);
		ConnManagerParams.setMaxConnectionsPerRoute(httpParams,
				new ConnPerRouteBean(maxConnections));
		ConnManagerParams.setMaxTotalConnections(httpParams,
				DEFAULT_MAX_CONNECTIONS);
		HttpConnectionParams.setSoTimeout(httpParams, socketTimeout);
		HttpConnectionParams.setTcpNoDelay(httpParams, true);
		HttpProtocolParams.setVersion(httpParams, HttpVersion.HTTP_1_1);
		HttpProtocolParams.setUserAgent(httpParams, httpUserAgent);

		SchemeRegistry schemeRegistry = new SchemeRegistry();
		schemeRegistry.register(new Scheme("http", PlainSocketFactory
				.getSocketFactory(), 80));
		schemeRegistry.register(new Scheme("https", SSLSocketFactory
				.getSocketFactory(), 443));

		ThreadSafeClientConnManager cm = new ThreadSafeClientConnManager(
				httpParams, schemeRegistry);
		httpClient = new DefaultHttpClient(cm, httpParams);
	}

	/**
	 * Intercept requests to have them ask for GZip encoding and intercept
	 * responses to automatically wrap the response entity for reinflation. This
	 * code is based on code from SyncService in the Google I/O 2010
	 * {@linkplain http://code.google.com/p/iosched/ scheduling app}.
	 */
	public static void enableGZIPEncoding() {
		httpClient.addRequestInterceptor(new GZIPHttpRequestInterceptor());
		httpClient.addResponseInterceptor(new GZIPHttpResponseInterceptor());
	}

	public static void setHttpClient(AbstractHttpClient httpClient) {
		BetterHttp.httpClient = httpClient;
	}

	public static AbstractHttpClient getHttpClient() {
		return httpClient;
	}

	public static BetterHttpRequest get(String url, String host) {
		return new HttpGet(httpClient, url, host, defaultHeaders);
	}

	public static BetterHttpRequest post(String url) {
		return new HttpPost(httpClient, url, defaultHeaders);
	}

	public static BetterHttpRequest post(String url, HttpEntity payload) {
		return new HttpPost(httpClient, url, payload, defaultHeaders);
	}

	public static BetterHttpRequest put(String url) {
		return new HttpPut(httpClient, url, defaultHeaders);
	}

	public static BetterHttpRequest put(String url, HttpEntity payload) {
		return new HttpPut(httpClient, url, payload, defaultHeaders);
	}

	public static BetterHttpRequest delete(String url) {
		return new HttpDelete(httpClient, url, defaultHeaders);
	}

	public static void setMaximumConnections(int maxConnections) {
		BetterHttp.maxConnections = maxConnections;
	}

	/**
	 * Adjust the socket timeout, i.e. the amount of time that may pass when
	 * waiting for a server response. Time unit is milliseconds.
	 * 
	 * @param socketTimeout
	 *            the timeout in milliseconds
	 */
	public static void setSocketTimeout(int socketTimeout) {
		BetterHttp.socketTimeout = socketTimeout;
		HttpConnectionParams
				.setSoTimeout(httpClient.getParams(), socketTimeout);
	}

	public static int getSocketTimeout() {
		return socketTimeout;
	}

	public static void setDefaultHeader(String header, String value) {
		defaultHeaders.put(header, value);
	}

	public static HashMap<String, String> getDefaultHeaders() {
		return defaultHeaders;
	}

	public static void setPortForScheme(String scheme, int port) {
		Scheme _scheme = new Scheme(scheme,
				PlainSocketFactory.getSocketFactory(), port);
		httpClient.getConnectionManager().getSchemeRegistry().register(_scheme);
	}

	public static void setUserAgent(String userAgent) {
		BetterHttp.httpUserAgent = userAgent;
		HttpProtocolParams.setUserAgent(httpClient.getParams(), userAgent);
	}

	/**
	 * Simple {@link HttpRequestInterceptor} that adds GZIP accept encoding
	 * header.
	 */
	static class GZIPHttpRequestInterceptor implements HttpRequestInterceptor {
		@Override
		public void process(final HttpRequest request, final HttpContext context) {
			// Add header to accept gzip content
			if (!request.containsHeader(HEADER_ACCEPT_ENCODING)) {
				request.addHeader(HEADER_ACCEPT_ENCODING, ENCODING_GZIP);
			}
		}
	}

	/**
	 * Simple {@link HttpResponseInterceptor} that inflates response if GZIP
	 * encoding header.
	 */
	static class GZIPHttpResponseInterceptor implements HttpResponseInterceptor {
		@Override
		public void process(final HttpResponse response,
				final HttpContext context) {
			// Inflate any responses compressed with gzip
			final HttpEntity entity = response.getEntity();
			final Header encoding = entity.getContentEncoding();
			if (encoding != null) {
				for (HeaderElement element : encoding.getElements()) {
					if (element.getName().equalsIgnoreCase(ENCODING_GZIP)) {
						response.setEntity(new GZIPInflatingEntity(response
								.getEntity()));
						break;
					}
				}
			}
		}
	}

	/**
	 * Simple {@link HttpEntityWrapper} that inflates the wrapped
	 * {@link HttpEntity} by passing it through {@link GZIPInputStream}.
	 */
	static class GZIPInflatingEntity extends HttpEntityWrapper {
		public GZIPInflatingEntity(final HttpEntity wrapped) {
			super(wrapped);
		}

		@Override
		public InputStream getContent() throws IOException {
			return new GZIPInputStream(wrappedEntity.getContent());
		}

		@Override
		public long getContentLength() {
			return -1;
		}
	}
}
