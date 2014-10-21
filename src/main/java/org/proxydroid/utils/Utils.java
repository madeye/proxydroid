package org.proxydroid.utils;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.Signature;
import android.graphics.drawable.Drawable;
import android.os.Environment;
import android.util.Log;
import org.proxydroid.Exec;
import org.proxydroid.ProxyDroidService;
import org.proxydroid.R;

import java.io.*;
import java.util.ArrayList;

public class Utils {

  public final static String TAG = "ProxyDroid";
  public final static String DEFAULT_SHELL = "/system/bin/sh";
  public final static String DEFAULT_ROOT = "/system/bin/su";
  public final static String ALTERNATIVE_ROOT = "/system/xbin/su";
  public final static String DEFAULT_IPTABLES = "/data/data/org.proxydroid/iptables";
  public final static String ALTERNATIVE_IPTABLES = "/system/bin/iptables";
  public final static int TIME_OUT = -99;
  private static boolean initialized = false;
  private static int hasRedirectSupport = -1;
  private static int isRoot = -1;
  private static String shell = null;
  private static String root_shell = null;
  private static String iptables = null;
  private static String data_path = null;
  private static boolean isConnecting = false;

  public static boolean isConnecting() {
    return isConnecting;
  }

  public static void setConnecting(boolean value) {
    isConnecting = value;
  }

  public static String preserve(String s) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '\\' || c == '$' || c == '`' || c == '"')
        sb.append('\\');
      sb.append(c);
    }
    return sb.toString();
  }

  private static void checkIptables() {

    if (!isRoot()) {
      iptables = DEFAULT_IPTABLES;
      return;
    }

    // Check iptables binary
    iptables = DEFAULT_IPTABLES;

    String lines = null;

    boolean compatible = false;
    boolean version = false;

    StringBuilder sb = new StringBuilder();
    String command = iptables + " --version\n" + iptables
        + " -L -t nat -n\n" + "exit\n";

    int exitcode = runScript(command, sb, 10 * 1000, true);

    if (exitcode == TIME_OUT)
      return;

    lines = sb.toString();

    if (lines.contains("OUTPUT")) {
      compatible = true;
    }
    if (lines.contains("v1.4.")) {
      version = true;
    }

    if (!compatible || !version) {
      iptables = ALTERNATIVE_IPTABLES;
      if (!new File(iptables).exists())
        iptables = "iptables";
    }

  }

  public static String getDataPath(Context ctx) {

    if (data_path == null) {

      if (Environment.MEDIA_MOUNTED.equals(Environment
          .getExternalStorageState())) {
        data_path = Environment.getExternalStorageDirectory()
            .getAbsolutePath();
      } else {
        data_path = "/sdcard";
      }

      Log.d(TAG, "Python Data Path: " + data_path);
    }

    return data_path;
  }

  public static boolean getHasRedirectSupport() {
    if (hasRedirectSupport == -1)
      initHasRedirectSupported();
    return hasRedirectSupport == 1;
  }

  public static String getIptables() {
    if (iptables == null)
      checkIptables();
    return iptables;
  }

  private static String getShell() {
    if (shell == null) {
      shell = DEFAULT_SHELL;
      if (!new File(shell).exists())
        shell = "sh";
    }
    return shell;
  }

  public static String getSignature(Context ctx) {
    Signature sig = null;
    try {
      Signature[] sigs;
      sigs = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(),
          PackageManager.GET_SIGNATURES).signatures;
      if (sigs != null && sigs.length > 0)
        sig = sigs[0];
    } catch (Exception ignore) {
      // Nothing
    }
    if (sig == null)
      return null;
    return sig.toCharsString().substring(11, 256);
  }

  public static void initHasRedirectSupported() {

    if (!Utils.isRoot())
      return;

    StringBuilder sb = new StringBuilder();
    String command = Utils.getIptables()
        + " -t nat -A OUTPUT -p udp --dport 54 -j REDIRECT --to 8154";

    int exitcode = runScript(command, sb, 10 * 1000, true);

    String lines = sb.toString();

    hasRedirectSupport = 1;

    // flush the check command
    Utils.runRootCommand(command.replace("-A", "-D"));

    if (exitcode == TIME_OUT)
      return;

    if (lines.contains("No chain/target/match")) {
      hasRedirectSupport = 0;
    }
  }

  public static boolean isInitialized() {
    if (initialized)
      return true;
    else {
      initialized = true;
      return false;
    }

  }

  public static boolean isRoot() {

    if (isRoot != -1)
      return isRoot == 1;

    // switch between binaries
    if (new File(DEFAULT_ROOT).exists()) {
      root_shell = DEFAULT_ROOT;
    } else if (new File(ALTERNATIVE_ROOT).exists()) {
      root_shell = ALTERNATIVE_ROOT;
    } else {
      root_shell = "su";
    }

    String lines = null;

    StringBuilder sb = new StringBuilder();
    String command = "ls /\n" + "exit\n";

    int exitcode = runScript(command, sb, 10 * 1000, true);

    if (exitcode == TIME_OUT) {
      return false;
    }

    lines = sb.toString();

    if (lines.contains("system")) {
      isRoot = 1;
    }

    return isRoot == 1;
  }

  public static boolean runCommand(String command, int timeout) {

    Log.d(TAG, command);

    runScript(command, null, timeout, false);

    return true;
  }

  public static boolean runCommand(String command) {

    return runCommand(command, 10 * 1000);

  }

  public static boolean runRootCommand(String command, int timeout) {

    Log.d(TAG, command);

    runScript(command, null, timeout, true);

    return true;
  }

  public static boolean runRootCommand(String command) {

    return runRootCommand(command, 10 * 1000);

  }

  private synchronized static int runScript(String script, StringBuilder res,
                                            long timeout, boolean asroot) {
    final ScriptRunner runner = new ScriptRunner(script, res, asroot);
    runner.start();
    try {
      if (timeout > 0) {
        runner.join(timeout);
      } else {
        runner.join();
      }
      if (runner.isAlive()) {
        // Timed-out
        runner.destroy();
        runner.join(1000);
        return TIME_OUT;
      }
    } catch (InterruptedException ex) {
      return TIME_OUT;
    }
    return runner.exitcode;
  }

  public static boolean isWorking() {
    return ProxyDroidService.isServiceStarted();
  }

  public static void CopyStream(InputStream is, OutputStream os) {
    final int buffer_size = 1024;
    try {
      byte[] bytes = new byte[buffer_size];
      for (; ; ) {
        int count = is.read(bytes, 0, buffer_size);
        if (count == -1)
          break;
        os.write(bytes, 0, count);
      }
    } catch (Exception ignored) {
    }
  }

  public static Drawable getAppIcon(Context c, int uid) {
    PackageManager pm = c.getPackageManager();
    Drawable appIcon = c.getResources().getDrawable(
        R.drawable.sym_def_app_icon);
    String[] packages = pm.getPackagesForUid(uid);

    if (packages != null) {
      if (packages.length == 1) {
        try {
          ApplicationInfo appInfo = pm.getApplicationInfo(
              packages[0], 0);
          appIcon = pm.getApplicationIcon(appInfo);
        } catch (NameNotFoundException e) {
          Log.e(c.getPackageName(),
              "No package found matching with the uid " + uid);
        }
      }
    } else {
      Log.e(c.getPackageName(), "Package not found for uid " + uid);
    }

    return appIcon;
  }

  /**
   * Internal thread used to execute scripts (as root or not).
   */
  private static final class ScriptRunner extends Thread {
    private final String scripts;
    private final StringBuilder result;
    private final boolean asroot;
    public int exitcode = -1;
    private int[] pid = new int[1];
    private FileDescriptor pipe;

    /**
     * Creates a new script runner.
     *
     * @param script script to run
     * @param result result output
     * @param asroot if true, executes the script as root
     */
    public ScriptRunner(String script, StringBuilder result,
                        boolean asroot) {
      this.scripts = script;
      this.result = result;
      this.asroot = asroot;
    }

    @Override
    public void destroy() {
      if (pid[0] != -1) {
        Exec.hangupProcessGroup(pid[0]);
        pid[0] = -1;
      }
      if (pipe != null) {
        Exec.close(pipe);
        pipe = null;
      }
    }

    private FileDescriptor createSubprocess(int[] processId, String cmd) {
      ArrayList<String> argList = parse(cmd);
      String arg0 = argList.get(0);
      String[] args = argList.toArray(new String[1]);

      return Exec.createSubprocess(result != null ? 1 : 0, arg0, args, null,
          scripts + "\nexit\n", processId);
    }

    private ArrayList<String> parse(String cmd) {
      final int PLAIN = 0;
      final int WHITESPACE = 1;
      final int INQUOTE = 2;
      int state = WHITESPACE;
      ArrayList<String> result = new ArrayList<String>();
      int cmdLen = cmd.length();
      StringBuilder builder = new StringBuilder();
      for (int i = 0; i < cmdLen; i++) {
        char c = cmd.charAt(i);
        if (state == PLAIN) {
          if (Character.isWhitespace(c)) {
            result.add(builder.toString());
            builder.delete(0, builder.length());
            state = WHITESPACE;
          } else if (c == '"') {
            state = INQUOTE;
          } else {
            builder.append(c);
          }
        } else if (state == WHITESPACE) {
          if (Character.isWhitespace(c)) {
            // do nothing
          } else if (c == '"') {
            state = INQUOTE;
          } else {
            state = PLAIN;
            builder.append(c);
          }
        } else if (state == INQUOTE) {
          if (c == '\\') {
            if (i + 1 < cmdLen) {
              i += 1;
              builder.append(cmd.charAt(i));
            }
          } else if (c == '"') {
            state = PLAIN;
          } else {
            builder.append(c);
          }
        }
      }
      if (builder.length() > 0) {
        result.add(builder.toString());
      }
      return result;
    }

    @Override
    public void run() {
      pid[0] = -1;

      try {

        if (this.asroot) {
          // Create the "su" request to run the script
          pipe = createSubprocess(pid, root_shell);
        } else {
          // Create the "sh" request to run the script
          pipe = createSubprocess(pid, getShell());
        }

        if (pid[0] != -1) {
          exitcode = Exec.waitFor(pid[0]);
        }

        if (result == null || pipe == null) return;

        final InputStream stdout = new FileInputStream(pipe);
        final byte buf[] = new byte[8192];
        int read = 0;

        // Read stdout
        while (stdout.available() > 0) {
          read = stdout.read(buf);
          result.append(new String(buf, 0, read));
        }

      } catch (Exception ex) {
        Log.e(TAG, "Cannot execute command", ex);
        if (result != null)
          result.append("\n").append(ex);
      } finally {
        if (pipe != null) {
          Exec.close(pipe);
        }
        if (pid[0] != -1) {
          Exec.hangupProcessGroup(pid[0]);
        }
      }
    }
  }
}
