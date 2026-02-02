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
package org.proxydroid

import org.proxydroid.utils.RegexValidator
import java.io.Serializable

class DomainValidator private constructor() : Serializable {

    companion object {
        private const val DOMAIN_LABEL_REGEX = "\\p{Alnum}(?>[\\p{Alnum}-]*\\p{Alnum})*"
        private const val TOP_LABEL_REGEX = "\\p{Alpha}{2,}"
        private const val DOMAIN_NAME_REGEX = "^(?:$DOMAIN_LABEL_REGEX\\.)+($TOP_LABEL_REGEX)$"

        @JvmStatic
        val instance: DomainValidator = DomainValidator()

        private val INFRASTRUCTURE_TLDS = arrayOf("arpa", "root")

        private val GENERIC_TLDS = arrayOf(
            "aero", "asia", "biz", "cat", "com", "coop", "info", "jobs", "mobi",
            "museum", "name", "net", "org", "pro", "tel", "travel", "gov", "edu", "mil", "int"
        )

        private val COUNTRY_CODE_TLDS = arrayOf(
            "ac", "ad", "ae", "af", "ag", "ai", "al", "am", "an", "ao", "aq", "ar", "as", "at",
            "au", "aw", "ax", "az", "ba", "bb", "bd", "be", "bf", "bg", "bh", "bi", "bj", "bm",
            "bn", "bo", "br", "bs", "bt", "bv", "bw", "by", "bz", "ca", "cc", "cd", "cf", "cg",
            "ch", "ci", "ck", "cl", "cm", "cn", "co", "cr", "cu", "cv", "cx", "cy", "cz", "de",
            "dj", "dk", "dm", "do", "dz", "ec", "ee", "eg", "er", "es", "et", "eu", "fi", "fj",
            "fk", "fm", "fo", "fr", "ga", "gb", "gd", "ge", "gf", "gg", "gh", "gi", "gl", "gm",
            "gn", "gp", "gq", "gr", "gs", "gt", "gu", "gw", "gy", "hk", "hm", "hn", "hr", "ht",
            "hu", "id", "ie", "il", "im", "in", "io", "iq", "ir", "is", "it", "je", "jm", "jo",
            "jp", "ke", "kg", "kh", "ki", "km", "kn", "kp", "kr", "kw", "ky", "kz", "la", "lb",
            "lc", "li", "lk", "lr", "ls", "lt", "lu", "lv", "ly", "ma", "mc", "md", "me", "mg",
            "mh", "mk", "ml", "mm", "mn", "mo", "mp", "mq", "mr", "ms", "mt", "mu", "mv", "mw",
            "mx", "my", "mz", "na", "nc", "ne", "nf", "ng", "ni", "nl", "no", "np", "nr", "nu",
            "nz", "om", "pa", "pe", "pf", "pg", "ph", "pk", "pl", "pm", "pn", "pr", "ps", "pt",
            "pw", "py", "qa", "re", "ro", "rs", "ru", "rw", "sa", "sb", "sc", "sd", "se", "sg",
            "sh", "si", "sj", "sk", "sl", "sm", "sn", "so", "sr", "st", "su", "sv", "sy", "sz",
            "tc", "td", "tf", "tg", "th", "tj", "tk", "tl", "tm", "tn", "to", "tp", "tr", "tt",
            "tv", "tw", "tz", "ua", "ug", "uk", "um", "us", "uy", "uz", "va", "vc", "ve", "vg",
            "vi", "vn", "vu", "wf", "ws", "ye", "yt", "yu", "za", "zm", "zw"
        )

        private val INFRASTRUCTURE_TLD_LIST = INFRASTRUCTURE_TLDS.toList()
        private val GENERIC_TLD_LIST = GENERIC_TLDS.toList()
        private val COUNTRY_CODE_TLD_LIST = COUNTRY_CODE_TLDS.toList()
    }

    private val domainRegex = RegexValidator(DOMAIN_NAME_REGEX)

    fun isValid(domain: String): Boolean {
        val groups = domainRegex.match(domain)
        return if (groups != null && groups.isNotEmpty()) {
            isValidTld(groups[0])
        } else {
            false
        }
    }

    fun isValidTld(tld: String): Boolean {
        return isValidInfrastructureTld(tld) || isValidGenericTld(tld) || isValidCountryCodeTld(tld)
    }

    fun isValidInfrastructureTld(iTld: String): Boolean {
        return INFRASTRUCTURE_TLD_LIST.contains(chompLeadingDot(iTld.lowercase()))
    }

    fun isValidGenericTld(gTld: String): Boolean {
        return GENERIC_TLD_LIST.contains(chompLeadingDot(gTld.lowercase()))
    }

    fun isValidCountryCodeTld(ccTld: String): Boolean {
        return COUNTRY_CODE_TLD_LIST.contains(chompLeadingDot(ccTld.lowercase()))
    }

    private fun chompLeadingDot(str: String): String {
        return if (str.startsWith(".")) str.substring(1) else str
    }
}
