/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.proxydroid.utils

import java.io.Serializable
import java.util.regex.Pattern

class RegexValidator : Serializable {
    private val patterns: Array<Pattern>

    constructor(regex: String) : this(regex, true)

    constructor(regex: String, caseSensitive: Boolean) : this(arrayOf(regex), caseSensitive)

    constructor(regexs: Array<String>) : this(regexs, true)

    constructor(regexs: Array<String>, caseSensitive: Boolean) {
        require(regexs.isNotEmpty()) { "Regular expressions are missing" }
        val flags = if (caseSensitive) 0 else Pattern.CASE_INSENSITIVE
        patterns = Array(regexs.size) { i ->
            require(regexs[i].isNotEmpty()) { "Regular expression[$i] is missing" }
            Pattern.compile(regexs[i], flags)
        }
    }

    fun isValid(value: String?): Boolean {
        if (value == null) return false
        return patterns.any { it.matcher(value).matches() }
    }

    fun match(value: String?): Array<String>? {
        if (value == null) return null
        for (pattern in patterns) {
            val matcher = pattern.matcher(value)
            if (matcher.matches()) {
                val count = matcher.groupCount()
                return Array(count) { j -> matcher.group(j + 1) ?: "" }
            }
        }
        return null
    }

    fun validate(value: String?): String? {
        if (value == null) return null
        for (pattern in patterns) {
            val matcher = pattern.matcher(value)
            if (matcher.matches()) {
                val count = matcher.groupCount()
                if (count == 1) {
                    return matcher.group(1)
                }
                val buffer = StringBuilder()
                for (j in 0 until count) {
                    val component = matcher.group(j + 1)
                    if (component != null) {
                        buffer.append(component)
                    }
                }
                return buffer.toString()
            }
        }
        return null
    }

    override fun toString(): String {
        return buildString {
            append("RegexValidator{")
            patterns.forEachIndexed { index, pattern ->
                if (index > 0) append(",")
                append(pattern.pattern())
            }
            append("}")
        }
    }
}
