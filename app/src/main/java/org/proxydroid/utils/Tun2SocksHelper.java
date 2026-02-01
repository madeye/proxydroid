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

package org.proxydroid.utils;

import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Helper class to manage the tun2socks native library.
 * This class wraps the native JNI calls to the tun2socks library
 * which handles converting TUN interface traffic to SOCKS5 proxy connections.
 */
public class Tun2SocksHelper {

    private static final String TAG = "Tun2SocksHelper";

    static {
        try {
            System.loadLibrary("tun2socks");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load tun2socks library", e);
        }
    }

    private final int tunFd;
    private final int mtu;
    private final String socksHost;
    private final int socksPort;
    private final String socksUser;
    private final String socksPassword;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private Thread workerThread;

    /**
     * Create a new Tun2SocksHelper instance.
     *
     * @param tunFd        The file descriptor of the TUN interface
     * @param mtu          The MTU of the TUN interface
     * @param socksHost    The SOCKS5 proxy host
     * @param socksPort    The SOCKS5 proxy port
     * @param socksUser    The SOCKS5 proxy username (can be null)
     * @param socksPassword The SOCKS5 proxy password (can be null)
     */
    public Tun2SocksHelper(int tunFd, int mtu, String socksHost, int socksPort,
                           String socksUser, String socksPassword) {
        this.tunFd = tunFd;
        this.mtu = mtu;
        this.socksHost = socksHost;
        this.socksPort = socksPort;
        this.socksUser = socksUser != null ? socksUser : "";
        this.socksPassword = socksPassword != null ? socksPassword : "";
    }

    /**
     * Start the tun2socks process.
     */
    public void start() {
        if (isRunning.getAndSet(true)) {
            Log.w(TAG, "tun2socks is already running");
            return;
        }

        workerThread = new Thread(() -> {
            Log.i(TAG, "Starting tun2socks: " + socksHost + ":" + socksPort);
            int result = nativeStart(tunFd, mtu, socksHost, socksPort, socksUser, socksPassword);
            Log.i(TAG, "tun2socks exited with code: " + result);
            isRunning.set(false);
        }, "Tun2Socks-Worker");

        workerThread.start();
    }

    /**
     * Stop the tun2socks process.
     */
    public void stop() {
        if (!isRunning.getAndSet(false)) {
            return;
        }

        Log.i(TAG, "Stopping tun2socks");
        nativeStop();

        if (workerThread != null) {
            try {
                workerThread.join(2000);
                if (workerThread.isAlive()) {
                    workerThread.interrupt();
                }
            } catch (InterruptedException e) {
                Log.w(TAG, "Interrupted while waiting for tun2socks to stop");
            }
            workerThread = null;
        }
    }

    /**
     * Check if tun2socks is running.
     *
     * @return true if tun2socks is running
     */
    public boolean isRunning() {
        return isRunning.get();
    }

    // Native methods implemented in tun2socks_jni.cpp
    private native int nativeStart(int tunFd, int mtu, String socksHost, int socksPort,
                                   String socksUser, String socksPassword);
    private native void nativeStop();
}
