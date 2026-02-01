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

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Helper class to manage tun2socks process.
 * tun2socks converts TUN device traffic to SOCKS proxy protocol.
 */
public class Tun2SocksHelper {

    private static final String TAG = "Tun2SocksHelper";

    private Context context;
    private int tunFd;
    private int mtu;
    private String tunAddress;
    private String proxyUrl;
    private String dnsServer;

    private Thread tun2SocksThread;
    private volatile boolean running = false;

    // Native library loaded flag
    private static boolean libraryLoaded = false;

    static {
        try {
            System.loadLibrary("tun2socks");
            libraryLoaded = true;
            Log.d(TAG, "tun2socks library loaded successfully");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load tun2socks library", e);
            libraryLoaded = false;
        }
    }

    // Native methods
    private static native int startTun2Socks(int tunFd, int mtu, String tunAddr,
            String tunGateway, String proxyUrl, String dnsAddr);
    private static native void stopTun2Socks();

    public Tun2SocksHelper(Context context, int tunFd, int mtu, String tunAddress,
            String proxyUrl, String dnsServer) {
        this.context = context;
        this.tunFd = tunFd;
        this.mtu = mtu;
        this.tunAddress = tunAddress;
        this.proxyUrl = proxyUrl;
        this.dnsServer = dnsServer;
    }

    /**
     * Start tun2socks in a background thread
     */
    public boolean start() {
        if (!libraryLoaded) {
            Log.e(TAG, "Cannot start tun2socks: library not loaded");
            return false;
        }

        if (running) {
            Log.w(TAG, "tun2socks already running");
            return true;
        }

        running = true;

        tun2SocksThread = new Thread(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "Starting tun2socks thread");
                Log.d(TAG, "tunFd: " + tunFd);
                Log.d(TAG, "mtu: " + mtu);
                Log.d(TAG, "tunAddress: " + tunAddress);
                Log.d(TAG, "proxyUrl: " + proxyUrl);
                Log.d(TAG, "dnsServer: " + dnsServer);

                // Gateway is typically .1 of the subnet
                String tunGateway = tunAddress.substring(0, tunAddress.lastIndexOf('.')) + ".1";

                try {
                    int result = startTun2Socks(tunFd, mtu, tunAddress, tunGateway,
                            proxyUrl, dnsServer);
                    Log.d(TAG, "tun2socks exited with code: " + result);
                } catch (Exception e) {
                    Log.e(TAG, "Error running tun2socks", e);
                }

                running = false;
            }
        }, "tun2socks-thread");

        tun2SocksThread.start();

        // Give it a moment to start
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return running;
    }

    /**
     * Stop tun2socks
     */
    public void stop() {
        if (!running) {
            return;
        }

        Log.d(TAG, "Stopping tun2socks");
        running = false;

        if (libraryLoaded) {
            try {
                stopTun2Socks();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping tun2socks", e);
            }
        }

        if (tun2SocksThread != null) {
            try {
                tun2SocksThread.interrupt();
                tun2SocksThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            tun2SocksThread = null;
        }
    }

    /**
     * Check if tun2socks is running
     */
    public boolean isRunning() {
        return running && tun2SocksThread != null && tun2SocksThread.isAlive();
    }

    /**
     * Check if the native library is available
     */
    public static boolean isLibraryLoaded() {
        return libraryLoaded;
    }
}
