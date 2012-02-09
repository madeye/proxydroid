package com.btr.proxy.selector.pac;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URISyntaxException;
import java.net.URL;

import android.util.Log;

/*****************************************************************************
 * Script source that will load the content of a PAC file from an webserver. The
 * script content is cached once it was downloaded.
 * 
 * @author Bernd Rosstauscher (proxyvole@rosstauscher.de) Copyright 2009
 ****************************************************************************/

public class UrlPacScriptSource implements PacScriptSource {

	private final static String TAG = "ProxyDroid.PAC";

	private final String scriptUrl;
	private String scriptContent;
	private long expireAtMillis;

	/*************************************************************************
	 * Constructor
	 * 
	 * @param url
	 *            the URL to download the script from.
	 ************************************************************************/

	public UrlPacScriptSource(String url) {
		super();
		this.expireAtMillis = 0;
		this.scriptUrl = url;
	}

	/*************************************************************************
	 * getScriptContent
	 * 
	 * @see com.btr.proxy.selector.pac.PacScriptSource#getScriptContent()
	 ************************************************************************/

	@Override
	public synchronized String getScriptContent() throws IOException {
		if (this.scriptContent == null
				|| (this.expireAtMillis > 0 && this.expireAtMillis > System
						.currentTimeMillis())) {
			try {
				if (this.scriptUrl.startsWith("file:/")
						|| this.scriptUrl.indexOf(":/") == -1) {
					this.scriptContent = readPacFileContent(this.scriptUrl);
				} else {
					this.scriptContent = downloadPacContent(this.scriptUrl);
				}
			} catch (IOException e) {
				Log.e(TAG, "Loading script failed.", e);
				this.scriptContent = "";
				throw e;
			}
		}
		return this.scriptContent;
	}

	/*************************************************************************
	 * Reads a PAC script from a local file.
	 * 
	 * @param scriptUrl
	 * @return the content of the script file.
	 * @throws IOException
	 * @throws URISyntaxException
	 ************************************************************************/

	private String readPacFileContent(String scriptUrl) throws IOException {
		try {
			File file = null;
			if (scriptUrl.indexOf(":/") == -1) {
				file = new File(scriptUrl);
			} else {
				file = new File(new URL(scriptUrl).toURI());
			}
			BufferedReader r = new BufferedReader(new FileReader(file));
			StringBuilder result = new StringBuilder();
			try {
				String line;
				while ((line = r.readLine()) != null) {
					result.append(line).append("\n");
				}
			} finally {
				r.close();
			}
			return result.toString();
		} catch (Exception e) {
			Log.e(TAG, "File reading error.", e);
			throw new IOException(e.getMessage());
		}
	}

	/*************************************************************************
	 * Downloads the script from a webserver.
	 * 
	 * @param url
	 *            the URL to the script file.
	 * @return the script content.
	 * @throws IOException
	 *             on read error.
	 ************************************************************************/

	private String downloadPacContent(String url) throws IOException {
		if (url == null) {
			throw new IOException("Invalid PAC script URL: null");
		}

		HttpURLConnection con = (HttpURLConnection) new URL(url)
				.openConnection(Proxy.NO_PROXY);
		con.setConnectTimeout(15 * 1000);
		con.setReadTimeout(20 * 1000);
		con.setInstanceFollowRedirects(true);
		con.setRequestProperty("accept",
				"application/x-ns-proxy-autoconfig, */*;q=0.8");

		if (con.getResponseCode() != 200) {
			throw new IOException("Server returned: " + con.getResponseCode()
					+ " " + con.getResponseMessage());
		}

		// Read expire date.
		this.expireAtMillis = con.getExpiration();

		String charsetName = parseCharsetFromHeader(con.getContentType());
		BufferedReader r = new BufferedReader(new InputStreamReader(
				con.getInputStream(), charsetName));
		try {
			StringBuilder result = new StringBuilder();
			try {
				String line;
				while ((line = r.readLine()) != null) {
					result.append(line).append("\n");
				}
			} finally {
				r.close();
				con.disconnect();
			}
			return result.toString();
		} finally {
			r.close();
		}
	}

	/*************************************************************************
	 * Response Content-Type could be something like this:
	 * application/x-ns-proxy-autoconfig; charset=UTF-8
	 * 
	 * @param contentType
	 *            header field.
	 * @return the extracted charset if set else a default charset.
	 ************************************************************************/

	String parseCharsetFromHeader(String contentType) {
		String result = "ISO-8859-1";
		if (contentType != null) {
			String[] paramList = contentType.split(";");
			for (String param : paramList) {
				if (param.toLowerCase().trim().startsWith("charset")
						&& param.indexOf("=") != -1) {
					result = param.substring(param.indexOf("=") + 1).trim();
				}
			}
		}
		return result;
	}

	/***************************************************************************
	 * @see java.lang.Object#toString()
	 **************************************************************************/
	@Override
	public String toString() {
		return this.scriptUrl;
	}

}
