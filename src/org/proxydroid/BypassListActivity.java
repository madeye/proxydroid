// Copyright 2009 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// dbartists - Douban artists client for Android
// Copyright (C) 2011 Max Lv <max.c.lv@gmail.com>
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not
// use this file except in compliance with the License.  You may obtain a copy
// of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
// WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
// License for the specific language governing permissions and limitations
// under the License.
//
//
//                           ___====-_  _-====___
//                     _--^^^#####//      \\#####^^^--_
//                  _-^##########// (    ) \\##########^-_
//                 -############//  |\^^/|  \\############-
//               _/############//   (@::@)   \\############\_
//              /#############((     \\//     ))#############\
//             -###############\\    (oo)    //###############-
//            -#################\\  / VV \  //#################-
//           -###################\\/      \//###################-
//          _#/|##########/\######(   /\   )######/\##########|\#_
//          |/ |#/\#/\#/\/  \#/\##\  |  |  /##/\#/  \/\#/\#/\#| \|
//          `  |/  V  V  `   V  \#\| |  | |/#/  V   '  V  V  \|  '
//             `   `  `      `   / | |  | | \   '      '  '   '
//                              (  | |  | |  )
//                             __\ | |  | | /__
//                            (vvv(VVV)(VVV)vvv)
//
//                             HERE BE DRAGONS

package org.proxydroid;

import java.util.ArrayList;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.View.OnClickListener;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.AdapterView.OnItemLongClickListener;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

public class BypassListActivity extends Activity implements OnClickListener,
		OnItemClickListener, OnItemLongClickListener {

	private static final String TAG = BypassListActivity.class.getName();

	private static final int MSG_ADD_ADDR = 1;
	private static final int MSG_EDIT_ADDR = 2;
	private static final int MSG_DEL_ADDR = 3;
	private static final int MSG_ERR_ADDR = 4;

	private ListAdapter adapter;
	private ArrayList<String> bypassList;
	private Profile profile = new Profile();

	final Handler handler = new Handler() {
		@Override
		public void handleMessage(Message msg) {
			String addr;
			switch (msg.what) {
			case MSG_ERR_ADDR:
				Toast.makeText(BypassListActivity.this, R.string.err_addr, Toast.LENGTH_LONG).show();
				break;
			case MSG_ADD_ADDR:
				addr = (String) msg.obj;
				bypassList.add(addr);
				break;
			case MSG_EDIT_ADDR:
				addr = (String) msg.obj;
				bypassList.set(msg.arg1, addr);
				break;
			case MSG_DEL_ADDR:
				bypassList.remove(msg.arg1);
				break;
			}
			refreshList();
			super.handleMessage(msg);
		}
	};

	@Override
	public void onClick(View arg0) {
		switch (arg0.getId()) {
		case R.id.addBypassAddr:
			editAddr(MSG_ADD_ADDR, -1);
			break;
		}
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {

		// Remove title bar
		this.requestWindowFeature(Window.FEATURE_NO_TITLE);

		super.onCreate(savedInstanceState);

		setContentView(R.layout.bypass_list);
		TextView addButton = (TextView) findViewById(R.id.addBypassAddr);
		addButton.setOnClickListener(this);

		refreshList();
	}

	@Override
	public void onItemClick(AdapterView<?> parent, View view, int position,
			long id) {
		editAddr(MSG_EDIT_ADDR, position);
	}

	@Override
	public boolean onItemLongClick(AdapterView<?> parent, View view,
			int position, long id) {
		delAddr(position);
		return true;
	}

	private void delAddr(final int idx) {
		
		final String addr = bypassList.get(idx);
		
		AlertDialog ad = new AlertDialog
				.Builder(this)
				.setTitle(addr)
				.setMessage(R.string.bypass_del_text)
				.setPositiveButton(R.string.alert_dialog_ok,
						new DialogInterface.OnClickListener() {
							public void onClick(DialogInterface dialog,
									int whichButton) {
								/* User clicked OK so do some stuff */
								Message msg = new Message();
								msg.what = MSG_DEL_ADDR;
								msg.arg1 = idx;
								msg.obj = addr;
								handler.sendMessage(msg);
							}
						})
				.setNegativeButton(R.string.alert_dialog_cancel,
						new DialogInterface.OnClickListener() {
							public void onClick(DialogInterface dialog,
									int whichButton) {

								/* User clicked Cancel so do some stuff */
							}
						}).create();

		ad.show();
	}

	private void editAddr(final int msg, final int idx) {
		LayoutInflater factory = LayoutInflater.from(this);
		final View textEntryView = factory.inflate(
				R.layout.alert_dialog_text_entry, null);
		final EditText addrText = (EditText) textEntryView
				.findViewById(R.id.text_edit);

		if (msg == MSG_EDIT_ADDR)
			addrText.setText(bypassList.get(idx));
		else if (msg == MSG_ADD_ADDR)
			addrText.setText("0.0.0.0/0");

		AlertDialog ad = new AlertDialog.Builder(this)
				.setTitle(R.string.bypass_edit_title)
				.setView(textEntryView)
				.setPositiveButton(R.string.alert_dialog_ok,
						new DialogInterface.OnClickListener() {
							public void onClick(DialogInterface dialog,
									int whichButton) {
								/* User clicked OK so do some stuff */

								new Thread() {
									public void run() {
										EditText addrText = (EditText) textEntryView
												.findViewById(R.id.text_edit);
										String addr = addrText.getText()
												.toString();
										addr = Profile.validateAddr(addr);
										if (addr != null) {
											Message m = new Message();
											m.what = msg;
											m.arg1 = idx;
											m.obj = addr;
											handler.sendMessage(m);
										} else {
											handler.sendEmptyMessage(MSG_ERR_ADDR);
										}
									}
								}.start();

							}
						})
				.setNegativeButton(R.string.alert_dialog_cancel,
						new DialogInterface.OnClickListener() {
							public void onClick(DialogInterface dialog,
									int whichButton) {

								/* User clicked cancel so do some stuff */
							}
						}).create();
		ad.show();
	}

	private void refreshList() {

		SharedPreferences settings = PreferenceManager
				.getDefaultSharedPreferences(this);

		profile.getProfile(settings);

		if (bypassList != null) {
			profile.setBypassAddrs(Profile.encodeAddrs(bypassList
					.toArray(new String[bypassList.size()])));
			profile.setProfile(settings);
		}

		String[] addrs = Profile.decodeAddrs(profile.getBypassAddrs());
		bypassList = new ArrayList<String>();

		for (String addr : addrs) {
			bypassList.add(addr);
//			Log.d(TAG, addr);
		}

		final LayoutInflater inflater = getLayoutInflater();

		adapter = new ArrayAdapter<String>(this, R.layout.bypass_list_item,
				R.id.bypasslistItemText, bypassList) {
			public View getView(int position, View convertView, ViewGroup parent) {
				String addr;
				if (convertView == null) {
					// Inflate a new view
					convertView = inflater.inflate(R.layout.bypass_list_item,
							parent, false);
				}

				TextView item = (TextView) convertView
						.findViewById(R.id.bypasslistItemText);
				addr = bypassList.get(position);
				if (addr != null)
					item.setText(addr);

				return convertView;
			}
		};

		ListView list = (ListView) findViewById(R.id.BypassListView);
		list.setAdapter(adapter);
		list.setOnItemClickListener(this);
		list.setOnItemLongClickListener(this);
	}
}
