package org.proxydroid.utils

data class Option(
    val name: String,
    val data: String,
    val path: String
) : Comparable<Option> {

    override fun compareTo(other: Option): Int {
        return name.lowercase().compareTo(other.name.lowercase())
    }
}
