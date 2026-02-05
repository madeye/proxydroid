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

package org.proxydroid

import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import android.preference.PreferenceManager
import org.proxydroid.utils.Constraints
import org.proxydroid.utils.Utils
import java.io.*

class BypassListActivity : AppCompatActivity(), View.OnClickListener,
    AdapterView.OnItemClickListener, AdapterView.OnItemLongClickListener {

    companion object {
        private val TAG = BypassListActivity::class.java.name
        private const val MSG_ERR_ADDR = 0
        private const val MSG_ADD_ADDR = 1
        private const val MSG_EDIT_ADDR = 2
        private const val MSG_DEL_ADDR = 3
        private const val MSG_PRESET_ADDR = 4
        private const val MSG_IMPORT_ADDR = 5
        private const val MSG_EXPORT_ADDR = 6
    }

    private var adapter: ListAdapter? = null
    private var bypassList: ArrayList<String> = ArrayList()
    private val profile = Profile()

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_ERR_ADDR -> {
                    Toast.makeText(this@BypassListActivity, R.string.err_addr, Toast.LENGTH_LONG).show()
                }
                MSG_ADD_ADDR -> {
                    val addr = msg.obj as? String ?: return
                    bypassList.add(addr)
                }
                MSG_EDIT_ADDR -> {
                    val addr = msg.obj as? String ?: return
                    bypassList[msg.arg1] = addr
                }
                MSG_DEL_ADDR -> {
                    bypassList.removeAt(msg.arg1)
                }
                MSG_PRESET_ADDR -> {
                    val list = Constraints.PRESETS[msg.arg1]
                    reset(list)
                    return
                }
                MSG_EXPORT_ADDR -> {
                    val path = msg.obj as? String ?: return
                    Toast.makeText(this@BypassListActivity, "${getString(R.string.exporting)} $path", Toast.LENGTH_LONG).show()
                    return
                }
            }
            refreshList()
            super.handleMessage(msg)
        }
    }

    override fun onClick(arg0: View) {
        when (arg0.id) {
            R.id.addBypassAddr -> editAddr(MSG_ADD_ADDR, -1)
            R.id.presetBypassAddr -> presetAddr()
            R.id.importBypassAddr -> importAddr()
            R.id.exportBypassAddr -> exportAddr()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        setContentView(R.layout.bypass_list)

        findViewById<TextView>(R.id.addBypassAddr).setOnClickListener(this)
        findViewById<TextView>(R.id.presetBypassAddr).setOnClickListener(this)
        findViewById<TextView>(R.id.importBypassAddr).setOnClickListener(this)
        findViewById<TextView>(R.id.exportBypassAddr).setOnClickListener(this)

        refreshList()
    }

    override fun onItemClick(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
        editAddr(MSG_EDIT_ADDR, position)
    }

    override fun onItemLongClick(parent: AdapterView<*>?, view: View?, position: Int, id: Long): Boolean {
        delAddr(position)
        return true
    }

    private fun presetAddr() {
        AlertDialog.Builder(this)
            .setTitle(R.string.preset_button)
            .setNegativeButton(R.string.alert_dialog_cancel) { _, _ -> }
            .setSingleChoiceItems(R.array.presets_list, -1) { dialog, which ->
                if (which >= 0 && which < Constraints.PRESETS.size) {
                    val msg = Message.obtain().apply {
                        what = MSG_PRESET_ADDR
                        arg1 = which
                    }
                    handler.sendMessage(msg)
                }
                dialog.dismiss()
            }
            .create()
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == Constraints.IMPORT_REQUEST && resultCode == RESULT_OK) {
            val path = data?.getStringExtra(Constraints.FILE_PATH)
            if (path.isNullOrEmpty()) return

            val pd = ProgressDialog.show(this, "", getString(R.string.importing), true, true)

            val h = object : Handler(Looper.getMainLooper()) {
                override fun handleMessage(msg: Message) {
                    refreshList()
                    pd?.dismiss()
                }
            }

            Thread {
                try {
                    FileInputStream(path).use { input ->
                        BufferedReader(InputStreamReader(input)).use { br ->
                            bypassList.clear()
                            var line: String?
                            while (br.readLine().also { line = it } != null) {
                                Profile.validateAddr(line)?.let { bypassList.add(it) }
                            }
                        }
                    }
                } catch (e: FileNotFoundException) {
                    Log.e(TAG, "error to open file", e)
                } catch (e: IOException) {
                    Log.e(TAG, "error to read file", e)
                }
                h.sendEmptyMessage(MSG_IMPORT_ADDR)
            }.start()
        }
    }

    private fun importAddr() {
        startActivityForResult(Intent(this, FileChooser::class.java), Constraints.IMPORT_REQUEST)
    }

    private fun exportAddr() {
        val factory = LayoutInflater.from(this)
        val textEntryView = factory.inflate(R.layout.alert_dialog_text_entry, null)
        val path = textEntryView.findViewById<EditText>(R.id.text_edit)

        path.setText("${Utils.getDataPath(this)}/${profile.host}.opt")

        AlertDialog.Builder(this)
            .setTitle(R.string.export_button)
            .setView(textEntryView)
            .setPositiveButton(R.string.alert_dialog_ok) { _, _ ->
                val pathText = path.text?.toString() ?: return@setPositiveButton

                Thread {
                    try {
                        val file = File(pathText)
                        if (!file.exists()) file.createNewFile()

                        FileOutputStream(file).use { output ->
                            BufferedOutputStream(output).use { bw ->
                                for (addr in bypassList) {
                                    Profile.validateAddr(addr)?.let {
                                        bw.write("$it\n".toByteArray())
                                    }
                                }
                                bw.flush()
                            }
                            output.flush()
                        }
                    } catch (e: FileNotFoundException) {
                        Log.e(TAG, "error to open file", e)
                    } catch (e: IOException) {
                        Log.e(TAG, "error to write file", e)
                    }

                    val msg = Message.obtain().apply {
                        what = MSG_EXPORT_ADDR
                        obj = pathText
                    }
                    handler.sendMessage(msg)
                }.start()
            }
            .setNegativeButton(R.string.alert_dialog_cancel) { _, _ -> }
            .create()
            .show()
    }

    private fun delAddr(idx: Int) {
        val addr = bypassList[idx]

        AlertDialog.Builder(this)
            .setTitle(addr)
            .setMessage(R.string.bypass_del_text)
            .setPositiveButton(R.string.alert_dialog_ok) { _, _ ->
                val msg = Message.obtain().apply {
                    what = MSG_DEL_ADDR
                    arg1 = idx
                    obj = addr
                }
                handler.sendMessage(msg)
            }
            .setNegativeButton(R.string.alert_dialog_cancel) { _, _ -> }
            .create()
            .show()
    }

    private fun editAddr(msgType: Int, idx: Int) {
        val factory = LayoutInflater.from(this)
        val textEntryView = factory.inflate(R.layout.alert_dialog_text_entry, null)
        val addrText = textEntryView.findViewById<EditText>(R.id.text_edit)

        when (msgType) {
            MSG_EDIT_ADDR -> addrText.setText(bypassList[idx])
            MSG_ADD_ADDR -> addrText.setText("0.0.0.0/0")
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.bypass_edit_title)
            .setView(textEntryView)
            .setPositiveButton(R.string.alert_dialog_ok) { _, _ ->
                Thread {
                    val addr = addrText.text.toString()
                    val validated = Profile.validateAddr(addr)
                    if (validated != null) {
                        val msg = Message.obtain().apply {
                            what = msgType
                            arg1 = idx
                            obj = validated
                        }
                        handler.sendMessage(msg)
                    } else {
                        handler.sendEmptyMessage(MSG_ERR_ADDR)
                    }
                }.start()
            }
            .setNegativeButton(R.string.alert_dialog_cancel) { _, _ -> }
            .create()
            .show()
    }

    private fun reset(list: Array<String>) {
        val pd = ProgressDialog.show(this, "", getString(R.string.reseting), true, true)

        val h = object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                refreshList()
                pd?.dismiss()
            }
        }

        Thread {
            bypassList.clear()
            for (addr in list) {
                Profile.validateAddr(addr)?.let { bypassList.add(it) }
            }
            h.sendEmptyMessage(0)
        }.start()
    }

    private fun refreshList() {
        val settings = PreferenceManager.getDefaultSharedPreferences(this)
        profile.getProfile(settings)

        if (bypassList.isNotEmpty()) {
            profile.bypassAddrs = Profile.encodeAddrs(bypassList.toTypedArray())
            profile.setProfile(settings)
        }

        val addrs = Profile.decodeAddrs(profile.bypassAddrs)
        bypassList = ArrayList(addrs.toList())

        val inflater = layoutInflater

        adapter = object : ArrayAdapter<String>(this, R.layout.bypass_list_item, R.id.bypasslistItemText, bypassList) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: inflater.inflate(R.layout.bypass_list_item, parent, false)
                val item = view.findViewById<TextView>(R.id.bypasslistItemText)
                bypassList.getOrNull(position)?.let { item.text = it }
                return view
            }
        }

        val list = findViewById<ListView>(R.id.BypassListView)
        list.adapter = adapter
        list.onItemClickListener = this
        list.onItemLongClickListener = this
    }
}
