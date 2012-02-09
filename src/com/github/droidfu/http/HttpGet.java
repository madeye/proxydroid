/* Copyright (c) 2009 Matthias Kaeppler
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.droidfu.http;

import java.util.HashMap;

import org.apache.http.impl.client.AbstractHttpClient;

class HttpGet extends BetterHttpRequestBase {

	HttpGet(AbstractHttpClient httpClient, String url, String host,
			HashMap<String, String> defaultHeaders) {
		super(httpClient);
		request = new org.apache.http.client.methods.HttpGet(url);
		for (String header : defaultHeaders.keySet()) {
			request.setHeader(header, defaultHeaders.get(header));
		}
		request.setHeader("Host", host);
	}

}
