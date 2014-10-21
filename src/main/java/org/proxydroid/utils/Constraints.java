package org.proxydroid.utils;

public class Constraints {

	public static final String ONLY_3G = "2G/3G";
	public static final String ONLY_WIFI = "WIFI";
	public static final String WIFI_AND_3G = "WIFI/2G/3G";
	public static final String FILE_PATH = "file_path";
	public static final int IMPORT_REQUEST = 0;

	public static final String[][] PRESETS = {
			{},
			{ "127.0.0.1" },
			{ "127.0.0.1", "10.0.0.0/8", "192.168.0.0/16", "172.16.0.0/12" },
			{ "1.0.0.0/2", "66.114.0.0/16", "96.0.0.0/3", "128.0.0.0/4",
					"171.0.0.0/8", "175.0.0.0/8", "180.0.0.0/8", "182.0.0.0/8",
					"183.0.0.0/8", "202.0.0.0/8", "203.0.0.0/8", "210.0.0.0/8",
					"211.0.0.0/8", "216.0.0.0/5" } };

}
