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

import java.io.IOException;
import java.io.InputStream;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.entity.BufferedHttpEntity;
import org.apache.http.util.EntityUtils;

public class BetterHttpResponseImpl implements BetterHttpResponse {

	private HttpResponse response;
	private HttpEntity entity;

	public BetterHttpResponseImpl(HttpResponse response) throws IOException {
		this.response = response;
		HttpEntity temp = response.getEntity();
		if (temp != null) {
			entity = new BufferedHttpEntity(temp);
		}
	}

	@Override
	public HttpResponse unwrap() {
		return response;
	}

	@Override
	public InputStream getResponseBody() throws IOException {
		return entity.getContent();
	}

	@Override
	public byte[] getResponseBodyAsBytes() throws IOException {
		return EntityUtils.toByteArray(entity);
	}

	@Override
	public String getResponseBodyAsString() throws IOException {
		return EntityUtils.toString(entity);
	}

	@Override
	public int getStatusCode() {
		return this.response.getStatusLine().getStatusCode();
	}

	@Override
	public String getHeader(String header) {
		if (!response.containsHeader(header)) {
			return null;
		}
		return response.getFirstHeader(header).getValue();
	}
}
