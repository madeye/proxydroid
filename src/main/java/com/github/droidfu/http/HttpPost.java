/* Copyright (c) 2009 Matthias K�ppler
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

import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.impl.client.AbstractHttpClient;

class HttpPost extends BetterHttpRequestBase {

	HttpPost(AbstractHttpClient httpClient, String url,
			HashMap<String, String> defaultHeaders) {
		super(httpClient);
		this.request = new org.apache.http.client.methods.HttpPost(url);
		for (String header : defaultHeaders.keySet()) {
			request.setHeader(header, defaultHeaders.get(header));
		}
	}

	HttpPost(AbstractHttpClient httpClient, String url, HttpEntity payload,
			HashMap<String, String> defaultHeaders) {
		super(httpClient);
		this.request = new org.apache.http.client.methods.HttpPost(url);
		((HttpEntityEnclosingRequest) request).setEntity(payload);

		request.setHeader(HTTP_CONTENT_TYPE_HEADER, payload.getContentType()
				.getValue());
		for (String header : defaultHeaders.keySet()) {
			request.setHeader(header, defaultHeaders.get(header));
		}
	}

}
