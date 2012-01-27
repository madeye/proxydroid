package org.proxydroid;

import java.io.IOException;
import java.net.Socket;

import android.util.Log;

public class InnerSocketBuilder {

	private String proxyHost = "127.0.0.1";
	private int proxyPort = 1053;

	private Socket innerSocket = null;

	private boolean isConnected = false;

	private final String TAG = "CMWRAP->InnerSocketBuilder";

	/**
	 * 建立经由代理服务器至目标服务器的连接
	 * 
	 * @param proxyHost
	 *            代理服务器地址
	 * @param proxyPort
	 *            代理服务器端口
	 * @param target
	 *            目标服务器
	 */
	public InnerSocketBuilder(String proxyHost, int proxyPort, String target) {
		this.proxyHost = proxyHost;
		this.proxyPort = proxyPort;

		connect();
	}

	private void connect() {

		// starTime = System.currentTimeMillis();
		Log.v(TAG, "建立通道");

		try {
			innerSocket = new Socket(proxyHost, proxyPort);
			innerSocket.setKeepAlive(true);
			innerSocket.setSoTimeout(60 * 1000);
			isConnected = true;

		} catch (IOException e) {
			Log.e(TAG, "建立隧道失败：" + e.getLocalizedMessage());
		}
	}

	public Socket getSocket() {
		return innerSocket;
	}

	public boolean isConnected() {
		return this.isConnected;
	}

}
