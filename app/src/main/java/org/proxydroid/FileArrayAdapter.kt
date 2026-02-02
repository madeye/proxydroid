package org.proxydroid

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import org.proxydroid.utils.Option

class FileArrayAdapter(
    context: Context,
    private val resourceId: Int,
    private val items: List<Option>
) : ArrayAdapter<Option>(context, resourceId, items) {

    override fun getItem(position: Int): Option = items[position]

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(resourceId, null)

        val o = items[position]
        view.findViewById<TextView>(R.id.TextView01)?.text = o.name
        view.findViewById<TextView>(R.id.TextView02)?.text = o.data

        return view
    }
}
