package org.proxydroid;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.proxydroid.db.DNSResponse;
import org.proxydroid.db.DatabaseHelper;
import org.proxydroid.utils.Base64;

import android.content.Context;
import android.util.Log;

import com.github.droidfu.http.BetterHttp;
import com.github.droidfu.http.BetterHttpRequest;
import com.github.droidfu.http.BetterHttpResponse;
import com.j256.ormlite.android.apptools.OpenHelperManager;
import com.j256.ormlite.dao.Dao;

/**
 * DNS Proxy
 *
 * @author biaji
 */
public class DNSProxy implements Runnable {

  public static byte[] int2byte(int res) {
    byte[] targets = new byte[4];

    targets[0] = (byte) (res & 0xff);// 最低位
    targets[1] = (byte) ((res >> 8) & 0xff);// 次低位
    targets[2] = (byte) ((res >> 16) & 0xff);// 次高位
    targets[3] = (byte) (res >>> 24);// 最高位,无符号右移。
    return targets;
  }

  private final String TAG = "ProxyDroid";

  private final static int MAX_THREAD_NUM = 5;
  private final ExecutorService mThreadPool = Executors.newFixedThreadPool(MAX_THREAD_NUM);

  public HashSet<String> domains;

  private DatagramSocket srvSocket;

  private int srvPort = 8153;
  final protected int DNS_PKG_HEADER_LEN = 12;
  final private int[] DNS_HEADERS = {0, 0, 0x81, 0x80, 0, 0, 0, 0, 0, 0, 0,
      0};
  final private int[] DNS_PAYLOAD = {0xc0, 0x0c, 0x00, 0x01, 0x00, 0x01,
      0x00, 0x00, 0x00, 0x3c, 0x00, 0x04};

  final private int IP_SECTION_LEN = 4;

  private boolean inService = false;

  /**
   * DNS Proxy upper stream
   */
  private String dnsRelay = "74.125.224.208";

  private static final String CANT_RESOLVE = "Error";

  private DatabaseHelper helper;


  public DNSProxy(Context ctx, int port) {

    this.srvPort = port;

    BetterHttp.setupHttpClient();
    BetterHttp.setSocketTimeout(10 * 1000);

    domains = new HashSet<String>();

    OpenHelperManager.setOpenHelperClass(DatabaseHelper.class);

    if (helper == null) {
      helper = OpenHelperManager.getHelper(ctx,
          DatabaseHelper.class);
    }

    try {
      InetAddress addr = InetAddress.getByName("mail.google.com");
      dnsRelay = addr.getHostAddress();
    } catch (Exception ignore) {
      dnsRelay = "74.125.224.208";
    }

  }

  public int init() {
    try {
      srvSocket = new DatagramSocket(0,
          InetAddress.getByName("127.0.0.1"));
      inService = true;
      srvPort = srvSocket.getLocalPort();
      Log.e(TAG, "Start at port " + srvPort);
    } catch (SocketException e) {
      Log.e(TAG, "DNSProxy fail to init，port: " + srvPort, e);
    } catch (UnknownHostException e) {
      Log.e(TAG, "DNSProxy fail to init，port " + srvPort, e);
    }
    return srvPort;
  }

  /**
   * Add a domain name to cache.
   *
   * @param questDomainName domain name
   * @param answer fake answer
   */
  private synchronized void addToCache(String questDomainName, byte[] answer) {
    DNSResponse response = new DNSResponse(questDomainName);
    response.setDNSResponse(answer);
    try {
      Dao<DNSResponse, String> dnsCacheDao = helper.getDNSCacheDao();
      dnsCacheDao.createOrUpdate(response);
    } catch (Exception e) {
      Log.e(TAG, "Cannot open DAO", e);
    }
  }

  private synchronized DNSResponse queryFromCache(String questDomainName) {
    try {
      Dao<DNSResponse, String> dnsCacheDao = helper.getDNSCacheDao();
      return dnsCacheDao.queryForId(questDomainName);
    } catch (Exception e) {
      Log.e(TAG, "Cannot open DAO", e);
    }
    return null;
  }

  public void close() throws IOException {
    inService = false;
    srvSocket.close();
    if (helper != null) {
      OpenHelperManager.releaseHelper();
      helper = null;
    }
    Log.i(TAG, "DNS Proxy closed");
  }

  /*
    * Create a DNS response packet, which will send back to application.
    *
    * @author yanghong
    *
    * Reference to:
    *
    * Mini Fake DNS server (Python)
    * http://code.activestate.com/recipes/491264-mini-fake-dns-server/
    *
    * DOMAIN NAMES - IMPLEMENTATION AND SPECIFICATION
    * http://www.ietf.org/rfc/rfc1035.txt
    */
  protected byte[] createDNSResponse(byte[] quest, byte[] ips) {
    byte[] response = null;
    int start = 0;

    response = new byte[1024];

    for (int val : DNS_HEADERS) {
      response[start] = (byte) val;
      start++;
    }

    System.arraycopy(quest, 0, response, 0, 2); /* 0:2 */
    System.arraycopy(quest, 4, response, 4, 2); /* 4:6 -> 4:6 */
    System.arraycopy(quest, 4, response, 6, 2); /* 4:6 -> 7:9 */
    System.arraycopy(quest, DNS_PKG_HEADER_LEN, response, start,
        quest.length - DNS_PKG_HEADER_LEN); /* 12:~ -> 15:~ */
    start += quest.length - DNS_PKG_HEADER_LEN;

    for (int val : DNS_PAYLOAD) {
      response[start] = (byte) val;
      start++;
    }

    /* IP address in response */
    for (byte ip : ips) {
      response[start] = ip;
      start++;
    }

    byte[] result = new byte[start];
    System.arraycopy(response, 0, result, 0, start);
    Log.d(TAG, "DNS Response package size: " + start);

    return result;
  }

  /**
   * Get request domain from UDP packet.
   *
   * @param request dns udp packet
   * @return domain
   */
  protected String getRequestDomain(byte[] request) {
    String requestDomain = "";
    int reqLength = request.length;
    if (reqLength > 13) { // include packet body
      byte[] question = new byte[reqLength - 12];
      System.arraycopy(request, 12, question, 0, reqLength - 12);
      requestDomain = parseDomain(question);
      if (requestDomain.length() > 1)
        requestDomain = requestDomain.substring(0,
            requestDomain.length() - 1);
    }
    return requestDomain;
  }

  public int getServPort() {
    return this.srvPort;
  }

  public boolean isClosed() {
    return srvSocket.isClosed();
  }

  public boolean isInService() {
    return inService;
  }

  /**
   * load cache
   */
  private void loadCache() {
    try {
      Dao<DNSResponse, String> dnsCacheDao = helper.getDNSCacheDao();
      List<DNSResponse> list = dnsCacheDao.queryForAll();
      for (DNSResponse resp : list) {
        // expire after 3 days
        if ((System.currentTimeMillis() - resp.getTimestamp()) > 259200000L) {
          Log.d(TAG, "deleted: " + resp.getRequest());
          dnsCacheDao.delete(resp);
        }
      }
    } catch (Exception e) {
      Log.e(TAG, "Cannot open DAO", e);
    }
  }

  /**
   * Parse request for domain name.
   *
   * @param request dns request
   * @return domain name
   */
  private String parseDomain(byte[] request) {

    String result = "";
    int length = request.length;
    int partLength = request[0];
    if (partLength == 0)
      return result;
    try {
      byte[] left = new byte[length - partLength - 1];
      System.arraycopy(request, partLength + 1, left, 0, length
          - partLength - 1);
      result = new String(request, 1, partLength) + ".";
      result += parseDomain(left);
    } catch (Exception e) {
      Log.e(TAG, e.getLocalizedMessage());
    }
    return result;
  }

  /*
    * Parse IP string into byte, do validation.
    *
    * @param ip IP string
    *
    * @return IP in byte array
    */
  protected byte[] parseIPString(String ip) {
    byte[] result = null;
    int value;
    int i = 0;
    String[] ips = null;

    ips = ip.split("\\.");

    Log.d(TAG, "Start parse ip string: " + ip + ", Sectons: " + ips.length);

    if (ips.length != IP_SECTION_LEN) {
      Log.e(TAG, "Malformed IP string number of sections is: "
          + ips.length);
      return null;
    }

    result = new byte[IP_SECTION_LEN];

    for (String section : ips) {
      try {
        value = Integer.parseInt(section);

        /* 0.*.*.* and *.*.*.0 is invalid */
        if ((i == 0 || i == 3) && value == 0) {
          return null;
        }

        result[i] = (byte) value;
        i++;
      } catch (NumberFormatException e) {
        Log.e(TAG, "Malformed IP string section: " + section);
        return null;
      }
    }

    return result;
  }

  @Override
  public void run() {

    loadCache();

    byte[] qbuffer = new byte[576];

    while (true) {
      try {
        final DatagramPacket dnsq = new DatagramPacket(qbuffer,
            qbuffer.length);

        srvSocket.receive(dnsq);

        byte[] data = dnsq.getData();
        int dnsqLength = dnsq.getLength();
        final byte[] udpreq = new byte[dnsqLength];
        System.arraycopy(data, 0, udpreq, 0, dnsqLength);

        final String questDomain = getRequestDomain(udpreq);

        Log.d(TAG, "Resolving: " + questDomain);

        DNSResponse resp = queryFromCache(questDomain);

        if (resp != null) {

          sendDns(resp.getDNSResponse(), dnsq, srvSocket);
          Log.d(TAG, "DNS cache hit for " + questDomain);

        } else if (questDomain.toLowerCase().endsWith(".appspot.com")) {
          // for appspot.com
          byte[] ips = parseIPString(dnsRelay);
          byte[] answer = createDNSResponse(udpreq, ips);
          addToCache(questDomain, answer);
          sendDns(answer, dnsq, srvSocket);
          Log.d(TAG, "Custom DNS resolver gaednsproxy1.appspot.com");
        } else {

          synchronized (this) {
            if (domains.contains(questDomain))
              continue;
            else
              domains.add(questDomain);
          }

          Runnable runnable = new Runnable() {
            @Override
            public void run() {
              long startTime = System.currentTimeMillis();
              try {
                byte[] answer = fetchAnswerHTTP(udpreq);
                if (answer != null && answer.length != 0) {
                  addToCache(questDomain, answer);
                  sendDns(answer, dnsq, srvSocket);
                  Log.d(TAG,
                      "Success to get DNS response for "
                          + questDomain
                          + "，length: "
                          + answer.length
                          + " "
                          + (System
                          .currentTimeMillis() - startTime)
                          / 1000 + "s");
                } else {
                  Log.e(TAG,
                      "The size of DNS packet returned is 0");
                }
              } catch (Exception e) {
                // Nothing
                Log.e(TAG, "Failed to resolve " + questDomain
                    + ": " + e.getLocalizedMessage(), e);
              }
              synchronized (DNSProxy.this) {
                domains.remove(questDomain);
              }
            }
          };

          mThreadPool.execute(runnable);

        }

      } catch (SocketException e) {
        Log.e(TAG, "Socket Exception", e);
        break;
      } catch (NullPointerException e) {
        Log.e(TAG, "Srvsocket wrong", e);
        break;
      } catch (IOException e) {
        Log.e(TAG, "IO Exception", e);
      }
    }

  }

  /*
    * Resolve host name by access a DNSRelay running on GAE:
    *
    * Example:
    *
    * http://www.hosts.dotcloud.com/lookup.php?(domain name encoded)
    * http://gaednsproxy.appspot.com/?d=(domain name encoded)
    * http://dnsproxy.cloudfoundry.com/(domain name encoded)
    */
  private String resolveDomainName(String domain) {
    String ip = null;

    InputStream is;

    String url = "http://gaednsproxy.appspot.com/?d="
        + URLEncoder.encode(Base64.encodeBytes(Base64
        .encodeBytesToBytes(domain.getBytes())));
    Log.d(TAG, "DNS Relay URL: " + url);
    String host = "gaednsproxy.appspot.com";
    url = url.replace(host, dnsRelay);

    BetterHttpRequest conn = BetterHttp.get(url, host);

    try {
      BetterHttpResponse resp = conn.send();
      is = resp.getResponseBody();
      BufferedReader br = new BufferedReader(new InputStreamReader(is));
      ip = br.readLine();
    } catch (ConnectException e) {
      Log.e(TAG, "Failed to request URI: " + url, e);
    } catch (IOException e) {
      Log.e(TAG, "Failed to request URI: " + url, e);
    }

    return ip;
  }

  /*
    * Implement with http based DNS.
    */

  public byte[] fetchAnswerHTTP(byte[] quest) {
    byte[] result = null;
    String domain = getRequestDomain(quest);
    String ip = null;

    DomainValidator dv = DomainValidator.getInstance();

    /* Not support reverse domain name query */
    if (domain.endsWith("ip6.arpa") || domain.endsWith("in-addr.arpa")
        || !dv.isValid(domain)) {
      return createDNSResponse(quest, parseIPString("127.0.0.1"));
    }

    ip = resolveDomainName(domain);

    if (ip == null) {
      Log.e(TAG, "Failed to resolve domain name: " + domain);
      return null;
    }

    if (ip.equals(CANT_RESOLVE)) {
      return null;
    }

    byte[] ips = parseIPString(ip);
    if (ips != null) {
      result = createDNSResponse(quest, ips);
    }

    return result;
  }

  /**
   * Send dns response
   *
   * @param response  response packet
   * @param dnsq      request packet
   * @param srvSocket server socket
   */
  private void sendDns(byte[] response, DatagramPacket dnsq,
                       DatagramSocket srvSocket) {

    // 同步identifier
    System.arraycopy(dnsq.getData(), 0, response, 0, 2);

    DatagramPacket resp = new DatagramPacket(response, 0, response.length);
    resp.setPort(dnsq.getPort());
    resp.setAddress(dnsq.getAddress());

    try {
      srvSocket.send(resp);
    } catch (IOException e) {
      Log.e(TAG, "", e);
    }
  }

}
