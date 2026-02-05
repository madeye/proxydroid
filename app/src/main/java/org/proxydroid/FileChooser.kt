package org.proxydroid

import android.app.ListActivity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ListView
import android.widget.Toast
import org.proxydroid.utils.Constraints
import org.proxydroid.utils.Option
import org.proxydroid.utils.Utils
import java.io.File
import java.util.*

class FileChooser : ListActivity() {

    private lateinit var currentDir: File
    private var adapter: FileArrayAdapter? = null

    private fun fill(f: File) {
        val dirs = f.listFiles()
        title = "${getString(R.string.current_dir)}: ${f.name}"

        val dir = ArrayList<Option>()
        val fls = ArrayList<Option>()

        try {
            dirs?.forEach { ff ->
                if (ff.isDirectory) {
                    dir.add(Option(ff.name, getString(R.string.folder), ff.absolutePath))
                } else {
                    fls.add(Option(ff.name, "${getString(R.string.file_size)}${ff.length()}", ff.absolutePath))
                }
            }
        } catch (e: Exception) {
            // Ignore
        }

        Collections.sort(dir)
        Collections.sort(fls)
        dir.addAll(fls)

        if (!f.name.equals("sdcard", ignoreCase = true)) {
            f.parent?.let { parent ->
                dir.add(0, Option("..", getString(R.string.parent_dir), parent))
            }
        }

        adapter = FileArrayAdapter(this@FileChooser, R.layout.file_view, dir)
        listAdapter = adapter
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentDir = File(Utils.getDataPath(this))
        fill(currentDir)
    }

    private fun onFileClick(o: Option) {
        val inputFile = File(o.path)
        if (inputFile.exists() && inputFile.length() < 32 * 1024) {
            Toast.makeText(this, "${getString(R.string.file_toast)}${o.path}", Toast.LENGTH_SHORT).show()
            val i = Intent().apply {
                putExtra(Constraints.FILE_PATH, o.path)
            }
            setResult(RESULT_OK, i)
        } else {
            Toast.makeText(this, "${getString(R.string.file_error)}${o.path}", Toast.LENGTH_SHORT).show()
            setResult(RESULT_CANCELED)
        }
        finish()
    }

    override fun onListItemClick(l: ListView, v: View, position: Int, id: Long) {
        super.onListItemClick(l, v, position, id)
        val o = adapter?.getItem(position) ?: return
        if (o.path == null) return

        if (o.data.equals(getString(R.string.folder), ignoreCase = true) ||
            o.data.equals(getString(R.string.parent_dir), ignoreCase = true)) {
            currentDir = File(o.path)
            fill(currentDir)
        } else {
            onFileClick(o)
        }
    }
}
