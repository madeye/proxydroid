# Copyright (C) 2009 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
#
LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LIBEVENT_SOURCES := \
	buffer.c \
	bufferevent.c bufferevent_filter.c \
	bufferevent_openssl.c bufferevent_pair.c bufferevent_ratelim.c \
	bufferevent_sock.c epoll.c \
	epoll_sub.c evdns.c event.c \
    event_tagging.c evmap.c \
	evrpc.c evthread.c \
	evthread_pthread.c evutil.c \
	evutil_rand.c http.c \
	listener.c log.c poll.c \
	select.c signal.c strlcpy.c

LOCAL_MODULE := event
LOCAL_SRC_FILES := $(addprefix libevent/, $(LIBEVENT_SOURCES))
LOCAL_CFLAGS := -O2 -g -I$(LOCAL_PATH)/libevent \
	-I$(LOCAL_PATH)/libevent/include \
	-I$(LOCAL_PATH)/openssl/include

include $(BUILD_STATIC_LIBRARY)

include $(CLEAR_VARS)

REDSOCKS_SOURCES := base.c dnstc.c http-connect.c \
	log.c md5.c socks5.c \
	base64.c http-auth.c http-relay.c main.c \
	parser.c redsocks.c socks4.c utils.c

LOCAL_STATIC_LIBRARIES := libevent

LOCAL_MODULE := redsocks
LOCAL_SRC_FILES := $(addprefix redsocks/, $(REDSOCKS_SOURCES))
LOCAL_CFLAGS := -O2 -std=gnu99 -g -I$(LOCAL_PATH)/redsocks \
	-I$(LOCAL_PATH)/libevent/include \
	-I$(LOCAL_PATH)/libevent

include $(BUILD_EXECUTABLE)
