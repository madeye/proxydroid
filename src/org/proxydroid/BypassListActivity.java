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
 * 
 * 
 *                            ___====-_  _-====___
 *                      _--^^^#####//      \\#####^^^--_
 *                   _-^##########// (    ) \\##########^-_
 *                  -############//  |\^^/|  \\############-
 *                _/############//   (@::@)   \\############\_
 *               /#############((     \\//     ))#############\
 *              -###############\\    (oo)    //###############-
 *             -#################\\  / VV \  //#################-
 *            -###################\\/      \//###################-
 *           _#/|##########/\######(   /\   )######/\##########|\#_
 *           |/ |#/\#/\#/\/  \#/\##\  |  |  /##/\#/  \/\#/\#/\#| \|
 *           `  |/  V  V  `   V  \#\| |  | |/#/  V   '  V  V  \|  '
 *              `   `  `      `   / | |  | | \   '      '  '   '
 *                               (  | |  | |  )
 *                              __\ | |  | | /__
 *                             (vvv(VVV)(VVV)vvv)
 *
 *                              HERE BE DRAGONS
 *
 */

package org.proxydroid;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;

import org.proxydroid.utils.Constraints;
import org.proxydroid.utils.Utils;

import com.actionbarsherlock.app.SherlockActivity;
import com.actionbarsherlock.view.MenuItem;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.AdapterView.OnItemLongClickListener;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

public class BypassListActivity extends SherlockActivity implements
		OnClickListener, OnItemClickListener, OnItemLongClickListener {

	private static final String TAG = BypassListActivity.class.getName();

	private static final int MSG_ERR_ADDR = 0;
	private static final int MSG_ADD_ADDR = 1;
	private static final int MSG_EDIT_ADDR = 2;
	private static final int MSG_DEL_ADDR = 3;
	private static final int MSG_PRESET_ADDR = 4;
	private static final int MSG_IMPORT_ADDR = 5;
	private static final int MSG_EXPORT_ADDR = 6;

	private ListAdapter adapter;
	private ArrayList<String> bypassList;
	private Profile profile = new Profile();

	final Handler handler = new Handler() {
		@Override
		public void handleMessage(Message msg) {
			String addr;
			switch (msg.what) {
			case MSG_ERR_ADDR:
				Toast.makeText(BypassListActivity.this, R.string.err_addr,
						Toast.LENGTH_LONG).show();
				break;
			case MSG_ADD_ADDR:
				if (msg.obj == null)
					return;
				addr = (String) msg.obj;
				bypassList.add(addr);
				break;
			case MSG_EDIT_ADDR:
				if (msg.obj == null)
					return;
				addr = (String) msg.obj;
				bypassList.set(msg.arg1, addr);
				break;
			case MSG_DEL_ADDR:
				bypassList.remove(msg.arg1);
				break;
			case MSG_PRESET_ADDR:
				String[] list = Constraints.PRESETS[msg.arg1];
				reset(list);
				return;
			case MSG_EXPORT_ADDR:
				if (msg.obj == null)
					return;
				Toast.makeText(BypassListActivity.this,
						getString(R.string.exporting) + " " + (String) msg.obj,
						Toast.LENGTH_LONG).show();
				return;
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
		case R.id.presetBypassAddr:
			presetAddr();
			break;
		case R.id.importBypassAddr:
			importAddr();
			break;
		case R.id.exportBypassAddr:
			exportAddr();
			break;
		}
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case android.R.id.home:
			// app icon in action bar clicked; go home
//			Intent intent = new Intent(this, ProxyDroid.class);
//			intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
//			startActivity(intent);
			finish();
			return true;
		default:
			return super.onOptionsItemSelected(item);
		}
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {

		super.onCreate(savedInstanceState);

		getSupportActionBar().setDisplayHomeAsUpEnabled(true);

		setContentView(R.layout.bypass_list);
		TextView addButton = (TextView) findViewById(R.id.addBypassAddr);
		addButton.setOnClickListener(this);

		TextView presetButton = (TextView) findViewById(R.id.presetBypassAddr);
		presetButton.setOnClickListener(this);

		TextView importButton = (TextView) findViewById(R.id.importBypassAddr);
		importButton.setOnClickListener(this);

		TextView exportButton = (TextView) findViewById(R.id.exportBypassAddr);
		exportButton.setOnClickListener(this);

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

	private void presetAddr() {
		AlertDialog ad = new AlertDialog.Builder(this)
				.setTitle(R.string.preset_button)
				.setNegativeButton(R.string.alert_dialog_cancel,
						new DialogInterface.OnClickListener() {
							@Override
							public void onClick(DialogInterface dialog,
									int whichButton) {
								/* User clicked Cancel so do some stuff */
							}
						})
				.setSingleChoiceItems(R.array.presets_list, -1,
						new DialogInterface.OnClickListener() {
							@Override
							public void onClick(DialogInterface dialog,
									int which) {
								if (which >= 0
										&& which < Constraints.PRESETS.length) {
									Message msg = new Message();
									msg.what = MSG_PRESET_ADDR;
									msg.arg1 = which;
									handler.sendMessage(msg);
								}
								dialog.dismiss();
							}
						}).create();
		ad.show();
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		if (requestCode == Constraints.IMPORT_REQUEST) {
			if (resultCode == RESULT_OK) {
				if (data == null)
					return;
				final String path = data.getStringExtra(Constraints.FILE_PATH);
				if (path == null || path.equals(""))
					return;

				final ProgressDialog pd = ProgressDialog.show(this, "",
						getString(R.string.importing), true, true);

				final Handler h = new Handler() {
					@Override
					public void handleMessage(Message msg) {
						refreshList();
						if (pd != null) {
							pd.dismiss();
						}
					}
				};

				new Thread() {
					@Override
					public void run() {
						FileInputStream input;
						try {
							input = new FileInputStream(path);
							BufferedReader br = new BufferedReader(
									new InputStreamReader(input));
							bypassList.clear();
							while (true) {
								String line = br.readLine();
								if (line == null)
									break;
								String addr = Profile.validateAddr(line);
								if (addr != null)
									bypassList.add(addr);
							}
							br.close();
							input.close();
						} catch (FileNotFoundException e) {
							Log.e(TAG, "error to open file", e);
						} catch (IOException e) {
							Log.e(TAG, "error to read file", e);
						}
						h.sendEmptyMessage(MSG_IMPORT_ADDR);
					}
				}.start();
			}
		}
	}

	private void importAddr() {
		startActivityForResult(new Intent(this, FileChooser.class),
				Constraints.IMPORT_REQUEST);
	}

	private void exportAddr() {
		if (profile == null)
			return;

		LayoutInflater factory = LayoutInflater.from(this);
		final View textEntryView = factory.inflate(
				R.layout.alert_dialog_text_entry, null);
		final EditText path = (EditText) textEntryView
				.findViewById(R.id.text_edit);

		path.setText(Utils.getDataPath(this) + "/" + profile.getHost() + ".opt");

		AlertDialog ad = new AlertDialog.Builder(this)
				.setTitle(R.string.export_button)
				.setView(textEntryView)
				.setPositiveButton(R.string.alert_dialog_ok,
						new DialogInterface.OnClickListener() {
							@Override
							public void onClick(DialogInterface dialog,
									int whichButton) {
								if (path.getText() == null
										|| path.getText().toString() == null)
									dialog.dismiss();
								new Thread() {
									@Override
									public void run() {

										FileOutputStream output;
										try {
											File file = new File(path.getText()
													.toString());
											if (!file.exists())
												file.createNewFile();

											output = new FileOutputStream(file);
											BufferedOutputStream bw = new BufferedOutputStream(
													output);
											for (String addr : bypassList) {
												addr = Profile
														.validateAddr(addr);
												if (addr != null)
													bw.write((addr + "\n")
															.getBytes());
											}

											bw.flush();
											bw.close();
											output.flush();
											output.close();
										} catch (FileNotFoundException e) {
											Log.e(TAG, "error to open file", e);
										} catch (IOException e) {
											Log.e(TAG, "error to write file", e);
										}

										Message msg = new Message();
										msg.what = MSG_EXPORT_ADDR;
										msg.obj = path.getText().toString();
										handler.sendMessage(msg);
									}
								}.start();

							}
						})
				.setNegativeButton(R.string.alert_dialog_cancel,
						new DialogInterface.OnClickListener() {
							@Override
							public void onClick(DialogInterface dialog,
									int whichButton) {
								/* User clicked cancel so do some stuff */
							}
						}).create();
		ad.show();
	}

	private void delAddr(final int idx) {

		final String addr = bypassList.get(idx);

		AlertDialog ad = new AlertDialog.Builder(this)
				.setTitle(addr)
				.setMessage(R.string.bypass_del_text)
				.setPositiveButton(R.string.alert_dialog_ok,
						new DialogInterface.OnClickListener() {
							@Override
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
							@Override
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
							@Override
							public void onClick(DialogInterface dialog,
									int whichButton) {
								/* User clicked OK so do some stuff */

								new Thread() {
									@Override
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
							@Override
							public void onClick(DialogInterface dialog,
									int whichButton) {

								/* User clicked cancel so do some stuff */
							}
						}).create();
		ad.show();
	}

	private void reset(final String[] list) {

		final ProgressDialog pd = ProgressDialog.show(this, "",
				getString(R.string.reseting), true, true);

		final Handler h = new Handler() {
			@Override
			public void handleMessage(Message msg) {
				refreshList();
				if (pd != null) {
					pd.dismiss();
				}
			}
		};

		new Thread() {
			@Override
			public void run() {
				bypassList.clear();
				for (String addr : list) {
					addr = Profile.validateAddr(addr);
					if (addr != null)
						bypassList.add(addr);
				}
				h.sendEmptyMessage(0);
			}
		}.start();
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
			// Log.d(TAG, addr);
		}

		final LayoutInflater inflater = getLayoutInflater();

		adapter = new ArrayAdapter<String>(this, R.layout.bypass_list_item,
				R.id.bypasslistItemText, bypassList) {
			@Override
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
