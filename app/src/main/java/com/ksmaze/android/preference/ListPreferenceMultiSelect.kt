/* proxydroid - Global / Individual Proxy App for Android
 * Copyright (C) 2011 K's Maze <kafkasmaze@gmail.com>
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

package com.ksmaze.android.preference

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.preference.ListPreference
import android.util.AttributeSet

class ListPreferenceMultiSelect @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ListPreference(context, attrs) {

    companion object {
        private const val SEPARATOR = " , "

        @JvmStatic
        fun parseStoredValue(value: CharSequence?): Array<String>? {
            if (value == null || value.toString().isEmpty()) {
                return null
            }
            return value.toString().split(SEPARATOR).toTypedArray()
        }
    }

    private var mClickedDialogEntryIndices: BooleanArray = BooleanArray(entries?.size ?: 0)

    override fun setEntries(entries: Array<out CharSequence>?) {
        super.setEntries(entries)
        mClickedDialogEntryIndices = BooleanArray(entries?.size ?: 0)
    }

    override fun onPrepareDialogBuilder(builder: AlertDialog.Builder) {
        val entries = entries
        val entryValues = entryValues

        require(!(entries == null || entryValues == null || entries.size != entryValues.size)) {
            "ListPreference requires an entries array and an entryValues array which are both the same length"
        }

        restoreCheckedEntries()
        builder.setMultiChoiceItems(entries, mClickedDialogEntryIndices) { _, which, isChecked ->
            mClickedDialogEntryIndices[which] = isChecked
        }
    }

    private fun restoreCheckedEntries() {
        val entryValues = entryValues ?: return
        val vals = parseStoredValue(value) ?: return

        for (v in vals) {
            val trimmedVal = v.trim()
            for (i in entryValues.indices) {
                if (entryValues[i] == trimmedVal) {
                    mClickedDialogEntryIndices[i] = true
                    break
                }
            }
        }
    }

    override fun onDialogClosed(positiveResult: Boolean) {
        val entryValues = entryValues
        if (positiveResult && entryValues != null) {
            val valueBuilder = StringBuilder()
            for (i in entryValues.indices) {
                if (mClickedDialogEntryIndices[i]) {
                    valueBuilder.append(entryValues[i]).append(SEPARATOR)
                }
            }

            if (callChangeListener(valueBuilder)) {
                var finalValue = valueBuilder.toString()
                if (finalValue.isNotEmpty()) {
                    finalValue = finalValue.substring(0, finalValue.length - SEPARATOR.length)
                }
                value = finalValue
            }
        }
    }
}
