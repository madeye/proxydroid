## INTRO

Global Proxy App for Android System

ProxyDroid is distributed under GPLv3 with many other open source software, 
here is a list of them:

 * cntlm - authentication proxy: http://cntlm.sourceforge.net/
 * redsocks - transparent socks redirector: http://darkk.net.ru/redsocks/
 * netfilter/iptables - NAT module: http://www.netfilter.org/
 * transproxy - transparent proxy for HTTP: http://transproxy.sourceforge.net/
 * stunnel - multiplatform SSL tunneling proxy: http://www.stunnel.org/

## TRAVIS CI STATUS

[![Build Status](https://secure.travis-ci.org/madeye/proxydroid.png)](http://travis-ci.org/madeye/proxydroid)

[Nightly Builds](http://buildbot.sinaapp.com)

## PREREQUISITES

* JDK 1.6+
* Maven 3.0.5
* Android SDK r17+
* Android NDK r8+

* Local Maven Dependencies

  Use Maven Android SDK Deployer to install all android related dependencies.

  ```bash
  git clone https://github.com/mosabua/maven-android-sdk-deployer.git 
  pushd maven-android-sdk-deployer
  export ANDROID_HOME=/path/to/android/sdk
  mvn install -P 4.1
  popd
  ```

## BUILD

Invoke the building like this

```bash
  mvn clean install
```
