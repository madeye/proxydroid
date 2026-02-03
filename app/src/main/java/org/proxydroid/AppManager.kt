/* Copyright (c) 2009, Nathan Freitas, Orbot / The Guardian Project - http://openideals.com/guardian */
/* See LICENSE for licensing information */

package org.proxydroid

import android.app.Activity
import android.app.ProgressDialog
import android.content.Context
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import android.preference.PreferenceManager
import org.proxydroid.utils.ImageLoader
import org.proxydroid.utils.ImageLoaderFactory
import java.util.*

class AppManager : AppCompatActivity(), CompoundButton.OnCheckedChangeListener, View.OnClickListener {

    private var apps: Array<ProxyedApp>? = null
    private lateinit var listApps: ListView
    private lateinit var overlay: TextView
    private var pd: ProgressDialog? = null
    private var adapter: ListAdapter? = null
    private lateinit var dm: ImageLoader
    private var appsLoaded = false

    companion object {
        private const val MSG_LOAD_START = 1
        private const val MSG_LOAD_FINISH = 2
        const val PREFS_KEY_PROXYED = "Proxyed"

        @JvmStatic
        fun getProxyedApps(context: Context, self: Boolean): Array<ProxyedApp> {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val tordAppString = prefs.getString(PREFS_KEY_PROXYED, "") ?: ""
            val st = StringTokenizer(tordAppString, "|")
            val tordApps = Array(st.countTokens()) { st.nextToken() }
            Arrays.sort(tordApps)

            val pMgr = context.packageManager
            val lAppInfo = pMgr.getInstalledApplications(0)
            val vectorApps = Vector<ProxyedApp>()

            for (aInfo in lAppInfo) {
                if (aInfo.uid < 10000) continue

                val app = ProxyedApp().apply {
                    uid = aInfo.uid
                    username = pMgr.getNameForUid(uid) ?: ""
                    isProxyed = when {
                        aInfo.packageName == "org.proxydroid" -> self
                        else -> Arrays.binarySearch(tordApps, username) >= 0
                    }
                }

                if (app.isProxyed) {
                    vectorApps.add(app)
                }
            }

            return vectorApps.toTypedArray()
        }
    }

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_LOAD_START -> {
                    pd = ProgressDialog.show(this@AppManager, "", getString(R.string.loading), true, true)
                }
                MSG_LOAD_FINISH -> {
                    listApps.adapter = adapter
                    listApps.setOnScrollListener(object : AbsListView.OnScrollListener {
                        var visible = false

                        override fun onScrollStateChanged(view: AbsListView, scrollState: Int) {
                            visible = true
                            if (scrollState == AbsListView.OnScrollListener.SCROLL_STATE_IDLE) {
                                overlay.visibility = View.INVISIBLE
                            }
                        }

                        override fun onScroll(view: AbsListView, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) {
                            if (visible && apps != null && firstVisibleItem < apps!!.size) {
                                val name = apps!![firstVisibleItem].name
                                overlay.text = if (name != null && name.length > 1) name.substring(0, 1) else "*"
                                overlay.visibility = View.VISIBLE
                            }
                        }
                    })

                    pd?.dismiss()
                    pd = null
                }
            }
            super.handleMessage(msg)
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
        setContentView(R.layout.layout_apps)

        dm = ImageLoaderFactory.getImageLoader(this)

        overlay = View.inflate(this, R.layout.overlay, null) as TextView
        windowManager.addView(
            overlay,
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
        )
    }

    override fun onDestroy() {
        windowManager.removeView(overlay)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()

        Thread {
            handler.sendEmptyMessage(MSG_LOAD_START)
            listApps = findViewById(R.id.applistview)
            if (!appsLoaded) loadApps()
            handler.sendEmptyMessage(MSG_LOAD_FINISH)
        }.start()
    }

    private fun loadApps() {
        getApps(this)

        apps?.sortWith { o1, o2 ->
            when {
                o1 == null || o2 == null || o1.name == null || o2.name == null -> 1
                o1.isProxyed == o2.isProxyed -> o1.name!!.compareTo(o2.name!!)
                o1.isProxyed -> -1
                else -> 1
            }
        }

        val inflater = layoutInflater

        adapter = object : ArrayAdapter<ProxyedApp>(this, R.layout.layout_apps_item, R.id.itemtext, apps!!) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val entry: ListEntry
                val view: View

                if (convertView == null) {
                    view = inflater.inflate(R.layout.layout_apps_item, parent, false)
                    entry = ListEntry(
                        view.findViewById(R.id.itemicon),
                        view.findViewById(R.id.itemcheck),
                        view.findViewById(R.id.itemtext)
                    )
                    entry.text.setOnClickListener(this@AppManager)
                    view.tag = entry
                    entry.box.setOnCheckedChangeListener(this@AppManager)
                } else {
                    view = convertView
                    entry = view.tag as ListEntry
                }

                val app = apps!![position]
                entry.icon.tag = app.uid
                dm.displayImage(app.uid, view.context as Activity, entry.icon)
                entry.text.text = app.name
                entry.box.tag = app
                entry.box.isChecked = app.isProxyed
                entry.text.tag = entry.box

                return view
            }
        }

        appsLoaded = true
    }

    private data class ListEntry(
        val icon: ImageView,
        val box: CheckBox,
        val text: TextView
    )

    override fun onStop() {
        super.onStop()
    }

    private fun getApps(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val tordAppString = prefs.getString(PREFS_KEY_PROXYED, "") ?: ""
        val st = StringTokenizer(tordAppString, "|")
        val tordApps = Array(st.countTokens()) { st.nextToken() }
        Arrays.sort(tordApps)

        val vectorApps = Vector<ProxyedApp>()
        val pMgr = context.packageManager
        val lAppInfo = pMgr.getInstalledApplications(0)

        for (aInfo in lAppInfo) {
            if (aInfo.uid < 10000) continue
            if (aInfo.processName == null) continue
            val label = pMgr.getApplicationLabel(aInfo)
            if (label == null || label.toString().isEmpty()) continue
            if (pMgr.getApplicationIcon(aInfo) == null) continue

            val tApp = ProxyedApp().apply {
                enabled = aInfo.enabled
                uid = aInfo.uid
                username = pMgr.getNameForUid(uid) ?: ""
                procname = aInfo.processName
                name = label.toString()
                isProxyed = Arrays.binarySearch(tordApps, username) >= 0
            }
            vectorApps.add(tApp)
        }

        apps = vectorApps.toTypedArray()
    }

    fun saveAppSettings(context: Context) {
        val currentApps = apps ?: return
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)

        val tordApps = StringBuilder()
        for (app in currentApps) {
            if (app.isProxyed) {
                tordApps.append(app.username)
                tordApps.append("|")
            }
        }

        prefs.edit().putString(PREFS_KEY_PROXYED, tordApps.toString()).apply()
    }

    override fun onCheckedChanged(buttonView: CompoundButton, isChecked: Boolean) {
        val app = buttonView.tag as? ProxyedApp
        app?.isProxyed = isChecked
        saveAppSettings(this)
    }

    override fun onClick(v: View) {
        val cbox = v.tag as CheckBox
        val app = cbox.tag as? ProxyedApp
        app?.let {
            it.isProxyed = !it.isProxyed
            cbox.isChecked = it.isProxyed
        }
        saveAppSettings(this)
    }

}
