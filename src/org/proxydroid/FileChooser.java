package org.proxydroid;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.proxydroid.utils.Constraints;
import org.proxydroid.utils.Option;
import org.proxydroid.utils.Utils;

import android.app.ListActivity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ListView;
import android.widget.Toast;

public class FileChooser extends ListActivity {

	private File currentDir;
	private FileArrayAdapter adapter;

	private void fill(File f) {
		File[] dirs = f.listFiles();
		this.setTitle(getString(R.string.current_dir) + ": " + f.getName());
		List<Option> dir = new ArrayList<Option>();
		List<Option> fls = new ArrayList<Option>();
		try {
			for (File ff : dirs) {
				if (ff.isDirectory())
					dir.add(new Option(ff.getName(),
							getString(R.string.folder), ff.getAbsolutePath()));
				else {
					fls.add(new Option(ff.getName(),
							getString(R.string.file_size) + ff.length(), ff
									.getAbsolutePath()));
				}
			}
		} catch (Exception e) {

		}
		Collections.sort(dir);
		Collections.sort(fls);
		dir.addAll(fls);
		if (!f.getName().equalsIgnoreCase("sdcard"))
			dir.add(0,
					new Option("..", getString(R.string.parent_dir), f
							.getParent()));
		adapter = new FileArrayAdapter(FileChooser.this, R.layout.file_view,
				dir);
		this.setListAdapter(adapter);
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		currentDir = new File(Utils.getDataPath(this));
		fill(currentDir);
	}

	private void onFileClick(Option o) {
		File inputFile = new File(o.getPath());
		if (inputFile.exists() && inputFile.length() < 32 * 1024) {
			Toast.makeText(this, getString(R.string.file_toast) + o.getPath(),
					Toast.LENGTH_SHORT).show();
			Intent i = new Intent();
			i.putExtra(Constraints.FILE_PATH, o.getPath());
			setResult(RESULT_OK, i);
		} else {
			Toast.makeText(this, getString(R.string.file_error) + o.getPath(),
					Toast.LENGTH_SHORT).show();
			setResult(RESULT_CANCELED);
		}

		finish();
	}

	@Override
	protected void onListItemClick(ListView l, View v, int position, long id) {
		super.onListItemClick(l, v, position, id);
		Option o = adapter.getItem(position);
		if (o.getData().equalsIgnoreCase(getString(R.string.folder))
				|| o.getData().equalsIgnoreCase(getString(R.string.parent_dir))) {
			currentDir = new File(o.getPath());
			fill(currentDir);
		} else {
			onFileClick(o);
		}
	}
}