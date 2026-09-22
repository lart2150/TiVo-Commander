/*
DVR Commander for TiVo allows control of a TiVo Premiere device.
Copyright (C) 2011  Anthony Lieuallen (arantius@gmail.com)

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License along
with this program; if not, write to the Free Software Foundation, Inc.,
51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
*/

package com.arantius.tivocommander.rpc;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.preference.PreferenceManager;
import android.widget.Toast;

import com.arantius.tivocommander.Connect;
import com.arantius.tivocommander.Database;
import com.arantius.tivocommander.Device;
import com.arantius.tivocommander.Discover;
import com.arantius.tivocommander.NowShowing;
import com.arantius.tivocommander.R;
import com.arantius.tivocommander.Settings;
import com.arantius.tivocommander.Utils;
import com.arantius.tivocommander.rpc.request.BodyAuthenticate;
import com.arantius.tivocommander.rpc.request.CancelRpc;
import com.arantius.tivocommander.rpc.request.MindRpcRequest;
import com.arantius.tivocommander.rpc.response.MindRpcResponse;
import com.arantius.tivocommander.rpc.response.MindRpcResponseListener;

public enum MindRpc {
  INSTANCE;

  private static class AlwaysTrustManager implements X509TrustManager {
    public void checkClientTrusted(X509Certificate[] cert, String authType)
        throws CertificateException {
    }

    public void checkServerTrusted(X509Certificate[] cert, String authType)
        throws CertificateException {
    }

    public X509Certificate[] getAcceptedIssuers() {
      return new X509Certificate[0];
    }
  }

  public static Boolean mBodyIsAuthed = false;
  public static volatile Boolean mConnectInterrupted = false;
  public static Device mTivoDevice;

  private static DataInputStream mInputStream;
  private static MindRpcInput mInputThread;
  private static Activity mOriginActivity;
  private static Bundle mOriginExtras;
  /** Set between sending the user to Connect and arriving there. */
  private static boolean mConnectPending = false;
  /** Null in the app; a test installs one to answer without a network. */
  private static MindRpcTransport mTransport;
  private static DataOutputStream mOutputStream;
  private static MindRpcOutput mOutputThread;
  private static TreeMap<Integer, MindRpcResponseListener> mResponseListenerMap =
      new TreeMap<Integer, MindRpcResponseListener>();
  private static volatile int mRpcId = 1;
  private static volatile int mSessionId;
  private static Socket mSocket;
  private static final int TIMEOUT_CONNECT = 25000;

  /** A request on the wire, still owed its first answer. */
  private static class Sent {
    final MindRpcRequest request;
    final int schema;
    final long deadline;

    Sent(MindRpcRequest request, int schema, long deadline) {
      this.request = request;
      this.schema = schema;
      this.deadline = deadline;
    }
  }

  /**
   * Everything written and not yet answered, by rpc id.
   *
   * The box answers every request but a cancel, so one that has gone
   * unanswered far longer than any request takes means the link is dead --
   * most often a socket the phone's Wi-Fi dropped while asleep, which still
   * looks open from this end and would otherwise leave a screen spinning
   * until the user gave up on it.
   */
  private static final Map<Integer, Sent> mAwaiting =
      new ConcurrentHashMap<Integer, Sent>();

  /** Set once the current connection has been given up on. */
  private static final AtomicBoolean mLinkLost = new AtomicBoolean(false);

  /** The certificate warning is shown once per run, not per connect try. */
  private static volatile boolean mWarnedCertExpiry = false;

  /** The box's answer to a request at a schema it does not speak. */
  private static final String UNSUPPORTED_SCHEMA = "Unsupported schema version";

  /** Warn this long before the client certificate runs out, as kmttg does. */
  private static final long CERT_WARN_DAYS = 90;

  /**
   * Add an outgoing request to the queue.
   *
   * @param request The request to be sent.
   * @param listener The object to notify when the response(s) come back.
   */
  public static void addRequest(MindRpcRequest request,
      MindRpcResponseListener listener) {
    if (mTransport != null) {
      // A test is standing in for the TiVo; it answers through
      // dispatchResponse(), so the listener still has to be registered first.
      if (listener != null) {
        mResponseListenerMap.put(request.getRpcId(), listener);
      }
      requestSent(request);
      mTransport.send(request);
      return;
    }

    // Reconnect if necessary; but not for BodyAuthenticate! That one RPC
    // is sent during connection as part of the verification.
    final MindRpcOutput output = mOutputThread;
    if (output == null
        || (!isConnected() && !(request instanceof BodyAuthenticate))) {
      init2();
      return;
    }
    // Registered before it is queued: the writer may send it, and the reader
    // receive the answer, before this thread runs another line.
    if (listener != null) {
      mResponseListenerMap.put(request.getRpcId(), listener);
    }
    output.addRequest(request);
  }

  /** The writer has put this on the wire; start waiting for its answer. */
  static void requestSent(MindRpcRequest request) {
    if (!request.expectsResponse()) {
      return;
    }
    mAwaiting.put(request.getRpcId(), new Sent(request,
        MindRpcRequest.getSchemaVersion(),
        System.currentTimeMillis() + request.getResponseTimeoutMs()));
  }

  /** Give up on the connection if something has waited too long. */
  static void checkOverdue() {
    final long now = System.currentTimeMillis();
    for (Map.Entry<Integer, Sent> entry : mAwaiting.entrySet()) {
      final Sent sent = entry.getValue();
      if (sent.deadline < now) {
        connectionLost(String.format(Locale.US, "no answer to %d %s in %ds",
            entry.getKey(), sent.request.getReqType(),
            TimeUnit.MILLISECONDS.toSeconds(
                sent.request.getResponseTimeoutMs())));
        return;
      }
    }
  }

  /**
   * The connection is gone, or as good as: the box hung up, a write failed,
   * or an answer is long overdue.
   *
   * Tear it down so isConnected() tells the truth, which is what sends every
   * screen's next init() or request through Connect.  If a working session
   * died under the screen in front, reconnect now rather than leave it
   * waiting on answers that will never come; a screen in the background
   * reconnects on its own when it resumes.
   */
  static void connectionLost(final String reason) {
    if (!mLinkLost.compareAndSet(false, true)) {
      return;
    }
    Utils.log("MindRpc: connection lost: " + reason);
    final boolean wasWorking = mBodyIsAuthed;
    mBodyIsAuthed = false;
    mAwaiting.clear();
    stopThreads();
    final Socket socket = mSocket;
    if (socket != null) {
      try {
        // Also what unblocks the reader, if it is still waiting on a read.
        socket.close();
      } catch (IOException e) {
        Utils.logError("connectionLost() socket", e);
      }
    }

    final Activity origin = mOriginActivity;
    if (!wasWorking || origin == null || mTransport != null) {
      // Lost while still connecting: Connect's own time limit covers that.
      return;
    }
    origin.runOnUiThread(new Runnable() {
      public void run() {
        if (origin != mOriginActivity || isConnected()) {
          return;
        }
        if (origin instanceof LifecycleOwner
            && ((LifecycleOwner) origin).getLifecycle().getCurrentState()
                .isAtLeast(Lifecycle.State.RESUMED)) {
          init2();
        }
      }
    });
  }

  private static boolean checkSettings(Activity activity) {
    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(
        activity.getBaseContext());

    Utils.DEBUG_LOG = prefs.getBoolean("debug_log", false);

    final Database db = new Database(activity);
    db.portLegacySettings(activity);
    mTivoDevice = db.getLastUsedDevice();

    int error = 0;
    if (mTivoDevice == null) {
      error = R.string.error_no_device;
    } else if (mTivoDevice.addr == null || "".equals(mTivoDevice.addr)) {
      error = R.string.error_addr;
    } else if (mTivoDevice.port == null || 0 >= mTivoDevice.port) {
      error = R.string.error_port;
    } else if (mTivoDevice.mak == null || "".equals(mTivoDevice.mak)) {
      error = R.string.error_mak;
    }

    if (error != 0) {
      settingsError(activity, error);
      return false;
    }

    return true;
  }

  private static boolean connect(final Activity originActivity) {
    Callable<Boolean> connectCallable = new Callable<Boolean>() {
      public Boolean call() {
        Utils.log(String.format(Locale.US,
            "Connecting to %s:%d ...", mTivoDevice.addr, mTivoDevice.port
            ));

        Utils.log(">>> With interfaces:");
        Enumeration<NetworkInterface> ifaces = null;
        try {
          ifaces = NetworkInterface.getNetworkInterfaces();
        } catch (SocketException e1) {
          Utils.log("Cannot get interfaces!");
        }
        while (ifaces != null && ifaces.hasMoreElements()) {
          NetworkInterface iface = ifaces.nextElement();
          StringBuilder ifaceStrBld = new StringBuilder();
          ifaceStrBld.append(String.format(Locale.US,
              "    %s %s",
              iface.getName(), iface.getDisplayName()
              ));

          boolean haveAddr = false;
          for (InterfaceAddress addr : iface.getInterfaceAddresses()) {
            if (addr.getAddress().isLoopbackAddress()) continue;
            if (addr.getAddress().isLinkLocalAddress()) continue;
            ifaceStrBld.append(", ");
            ifaceStrBld.append(addr.toString());
            haveAddr = true;
          }

          if (haveAddr) Utils.log(ifaceStrBld.toString());
        }
        Utils.log("<<<");

        SSLSocketFactory sslSocketFactory = createSocketFactory(originActivity);
        if (sslSocketFactory == null) {
          return false;
        }

        try {
          mSessionId = 0x26c000 + new Random().nextInt(0xFFFF);
          mSocket = sslSocketFactory.createSocket();
          InetSocketAddress remoteAddr =
              new InetSocketAddress(mTivoDevice.addr, mTivoDevice.port);
          mSocket.connect(remoteAddr, TIMEOUT_CONNECT);
          mInputStream = new DataInputStream(mSocket.getInputStream());
          mOutputStream = new DataOutputStream(mSocket.getOutputStream());
        } catch (UnknownHostException e) {
          Utils.logError("connect: unknown host!", e);
          return false;
        } catch (IOException e) {
          Utils.logError("connect: io exception!", e);
          return false;
        }

        return true;
      }
    };

    ExecutorService executor = new ScheduledThreadPoolExecutor(1);
    Future<Boolean> success = executor.submit(connectCallable);
    try {
      return success.get();
    } catch (InterruptedException e) {
      Utils.logError("connect: interrupted exception!", e);
      return false;
    } catch (ExecutionException e) {
      Utils.logError("connect: execution exception!", e);
      return false;
    }
  }

  @SuppressLint("TrulyRandom")
  private static SSLSocketFactory createSocketFactory(
      final Activity originActivity
      ) {
    final String password = readPassword(originActivity);
    try {
      KeyStore keyStore = KeyStore.getInstance("PKCS12");
      KeyManagerFactory fac = KeyManagerFactory.getInstance("X509");
      InputStream keyInput = originActivity.getResources().openRawResource(
          R.raw.cdata);

      keyStore.load(keyInput, password.toCharArray());
      keyInput.close();
      checkCertExpiry(keyStore, originActivity);

      fac.init(keyStore, password.toCharArray());
      SSLContext context = SSLContext.getInstance("TLS");
      TrustManager[] tm = new TrustManager[] { new AlwaysTrustManager() };
      context.init(fac.getKeyManagers(), tm, new SecureRandom());
      return context.getSocketFactory();
    } catch (CertificateException e) {
      Utils.logError("createSocketFactory: CertificateException!", e);
    } catch (IOException e) {
      Utils.logError("createSocketFactory: IOException!", e);
    } catch (KeyManagementException e) {
      Utils.logError("createSocketFactory: KeyManagementException!", e);
    } catch (KeyStoreException e) {
      Utils.logError("createSocketFactory: KeyStoreException!", e);
    } catch (NoSuchAlgorithmException e) {
      Utils.logError("createSocketFactory: NoSuchAlgorithmException!", e);
    } catch (UnrecoverableKeyException e) {
      Utils.logError("createSocketFactory: UnrecoverableKeyException!", e);
    }
    return null;
  }

  /**
   * Warn while there is still time to do something about it: once the client
   * certificate built into the app runs out, no TiVo will accept a connection
   * from it, and all the user sees is that connecting never works.
   */
  private static void checkCertExpiry(
      KeyStore keyStore, final Activity activity) throws KeyStoreException {
    long soonest = Long.MAX_VALUE;
    Enumeration<String> aliases = keyStore.aliases();
    while (aliases.hasMoreElements()) {
      X509Certificate cert =
          (X509Certificate) keyStore.getCertificate(aliases.nextElement());
      if (cert != null) {
        soonest = Math.min(soonest, cert.getNotAfter().getTime());
      }
    }
    if (soonest == Long.MAX_VALUE) {
      return;
    }
    final long days = certDaysLeft(soonest, System.currentTimeMillis());
    if (days >= CERT_WARN_DAYS) {
      return;
    }
    Utils.logError(String.format(Locale.US,
        "Client certificate expires in %d days (%s).", days, new Date(soonest)));
    if (mWarnedCertExpiry) {
      return;
    }
    mWarnedCertExpiry = true;
    activity.runOnUiThread(new Runnable() {
      public void run() {
        String message = days < 0
            ? activity.getString(R.string.cert_expired)
            : activity.getResources().getQuantityString(
                R.plurals.cert_expiring, (int) days, (int) days);
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
      }
    });
  }

  /** Whole days from now until the certificate stops working. */
  static long certDaysLeft(long notAfterMs, long nowMs) {
    return Math.floorDiv(notAfterMs - nowMs, TimeUnit.DAYS.toMillis(1));
  }

  /** Cancel all outstanding RPCs with response listeners. */
  public static void cancelAll() {
    for (Integer i : mResponseListenerMap.keySet()) {
      mAwaiting.remove(i);
      addRequest(new CancelRpc(i), null);
    }
    mResponseListenerMap.clear();
  }

  /**
   * Cancel one request, by the rpc id of the request that started it.
   *
   * Prefer this to cancelAll() for anything that is only trying to drop its
   * own stale response: cancelAll() empties the whole listener map, including
   * listeners something else is still waiting on -- the artwork lookups behind
   * a list of search results, say, whose rows are then left spinning forever.
   */
  public static void cancelRequest(int rpcId) {
    if (mResponseListenerMap.remove(rpcId) == null) {
      // Already answered, or never ours.
      return;
    }
    // A cancelled request may never be answered; stop timing it.
    mAwaiting.remove(rpcId);
    addRequest(new CancelRpc(rpcId), null);
  }

  public static void disconnect() {
    Thread disconnectThread = new Thread(new Runnable() {
      public void run() {
        // TODO: Do disconnect on close (after N idle seconds?).
        stopThreads();
        if (mSocket != null) {
          try {
            mSocket.close();
          } catch (IOException e) {
            Utils.logError("disconnect() socket", e);
          }
        }
        if (mInputStream != null) {
          try {
            mInputStream.close();
          } catch (IOException e) {
            Utils.logError("disconnect() input stream", e);
          }
        }
        if (mOutputStream != null) {
          try {
            mOutputStream.close();
          } catch (IOException e) {
            Utils.logError("disconnect() output stream", e);
          }
        }
      }
    });
    mBodyIsAuthed = false;
    disconnectThread.start();
    try {
      disconnectThread.join();
    } catch (InterruptedException e) {
      Utils.logError("disconnect() interrupted exception", e);
    }
  }

  /** Stand in for the TiVo, or pass null to go back to the socket. */
  static void setTransport(MindRpcTransport transport) {
    mTransport = transport;
  }

  protected static void dispatchResponse(final MindRpcResponse response) {
    final Integer rpcId = response.getRpcId();
    final Sent sent = mAwaiting.remove(rpcId);

    if (sent != null && sent.schema != MindRpcRequest.SCHEMA_VERSION_OLD
        && isUnsupportedSchema(response)) {
      // An older box.  Say it again at the schema it speaks, under the same
      // rpc id, so the listener hears only the answer -- as kmttg does,
      // except that it leaves the user to repeat the command.
      MindRpcRequest.fallBackToOldSchema();
      Utils.log("MindRpc: box refused schema " + sent.schema + "; now "
          + MindRpcRequest.getSchemaVersion());
      resend(sent.request);
      return;
    }

    if (mResponseListenerMap.get(rpcId) == null) {
      return;
    }

    mOriginActivity.runOnUiThread(new Runnable() {
      public void run() {
        MindRpcResponseListener l = mResponseListenerMap.get(rpcId);
        if (l != null) {
          // It might have been removed on another thread, so check for null.
          l.onResponse(response);
        }
        if (response.isFinal()) {
          mResponseListenerMap.remove(rpcId);
        }
      }
    });
  }

  /**
   * A new connection, perhaps to another box: start again from the newest
   * schema, with nothing owed, and ready to notice this one being lost.
   */
  static void connectionStarted() {
    MindRpcRequest.resetSchemaVersion();
    mAwaiting.clear();
    mLinkLost.set(false);
  }

  private static boolean isUnsupportedSchema(MindRpcResponse response) {
    return "error".equals(response.getRespType())
        && UNSUPPORTED_SCHEMA.equals(response.getBody().path("text").asText());
  }

  private static void resend(MindRpcRequest request) {
    if (mTransport != null) {
      requestSent(request);
      mTransport.send(request);
      return;
    }
    final MindRpcOutput output = mOutputThread;
    if (output != null) {
      output.addRequest(request);
    }
  }

  public static Boolean getBodyIsAuthed() {
    return mBodyIsAuthed;
  }

  public static int getRpcId() {
    return mRpcId++;
  }

  public static int getSessionId() {
    return mSessionId;
  }

  /** Init step 1.  Every activity that's going to do RPCs should call this,
   * passing itself and its data bundle, if any. */
  public static boolean init(final Activity originActivity, Bundle originExtras) {
    // Always save these; they're used to resume later.
    mOriginExtras = originExtras;
    mOriginActivity = originActivity;

    if (isConnected()) {
      // Already connected? No-op.
      mConnectPending = false;
      Utils.log("MindRpc.init(): already connected.");
      return false;
    }

    Utils.log("MindRpc.init(); " + originActivity.toString());
    if (!checkSettings(originActivity)) {
      originActivity.finish();
      return true;
    }

    init2();
    return true;
  }

  /** Init continues here; it may resume here after disconnection. Fires off
   * the Connect activity to surface this flow to the user. */
  public static void init2() {
    if (mConnectPending) {
      // Something else already sent us to Connect and we have not arrived
      // yet.  addRequest() routes here whenever the socket is down, so a
      // screen with several things loading at once -- ExploreTabs, with a
      // request per page -- would otherwise stack up one Connect activity per
      // request and finish its host that many times.
      Utils.log("MindRpc.init2(): connect already pending.");
      return;
    }
    mConnectPending = true;

    Utils.log("MindRpc.init2(); " + mOriginActivity.toString());
    Intent intent =
        new Intent(mOriginActivity.getBaseContext(), Connect.class);
    mOriginActivity.startActivity(intent);
    mOriginActivity.finish();
  }

  /** Finally, (only) the Connect activity calls back here to finish init. */
  public static void init3(final Activity connectActivity) {
    // Connect has us; further requests may queue another attempt if this one
    // does not pan out.
    mConnectPending = false;

    if (mOriginActivity == null) {
      // We must have been evicted/quit and restarted while at the connect
      // screen which calls us. Restart the app from Now Showing.
      Intent intent =
          new Intent(connectActivity.getBaseContext(), NowShowing.class);
      intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
      connectActivity.startActivity(intent);
      connectActivity.finish();
      return;
    }

    Utils.log("MindRpc.init3() " + mOriginActivity.toString());

    stopThreads();
    disconnect();

    // Try to connect in an infinite loop.  In case e.g. we need to wait for
    // WiFi to resume from sleep to be successful.  Only the Connect activity
    // calls init3(), and it is responsible for terminating this thread if
    // it takes too long.
    mConnectInterrupted = false;
    while (true) {
      if (mConnectInterrupted) {
        Utils.log("MindRpc.init3() interrupting connect attempt.");
        return;
      }
      if (connect(connectActivity)) {
        break;
      }
      try {
        Thread.sleep(500);
      } catch (InterruptedException e) {
        // Ignore.
      }
    }

    connectionStarted();

    mInputThread = new MindRpcInput(mInputStream);
    mInputThread.start();

    mOutputThread = new MindRpcOutput(mOutputStream);
    mOutputThread.start();

    MindRpcResponseListener authListener = new MindRpcResponseListener() {
      public void onResponse(MindRpcResponse response) {
        if ("failure".equals(response.getBody().path("status").asText())) {
          settingsError(connectActivity, R.string.error_auth);
          try {
            mTivoDevice.mak = "";
            new Database(connectActivity).saveDevice(mTivoDevice);
          } catch (Exception e) {
            Utils.logError("Could not remove bad MAK.", e);
          }
          connectActivity.finish();
        } else {
          mBodyIsAuthed = true;
          Intent intent =
              new Intent(connectActivity.getBaseContext(),
                  mOriginActivity.getClass());
          if (mOriginExtras != null) {
            intent.putExtras(mOriginExtras);
          }
          mOriginExtras = null;
          // FLAG_ACTIVITY_NO_ANIMATION replaces overridePendingTransition(0,
          // 0), deprecated in API 34.  Its successor
          // overrideActivityTransition() would need an API-level branch from
          // minSdk 29; the intent flag says "no transition" directly and has
          // been stable since API 5.
          intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
          connectActivity.startActivity(intent);
          connectActivity.finish();
        }
      }
    };
    Utils.log("MindRpc.init3(): start auth.");
    addRequest(new BodyAuthenticate(mTivoDevice.mak), authListener);
  }

  protected static boolean isConnected() {
    if (mTransport != null) {
      // Whatever is standing in for the TiVo is always ready.
      return true;
    }
    if (mInputThread == null
        || mInputThread.getState() == Thread.State.TERMINATED
        || mOutputThread == null
        || mOutputThread.getState() == Thread.State.TERMINATED) {
      mBodyIsAuthed = false;
      return false;
    }
    if (mSocket == null || mSocket.isClosed()) {
      mBodyIsAuthed = false;
      return false;
    }

    return mBodyIsAuthed;
  }

  private static String readPassword(Context ctx) {
    InputStream inputStream = ctx.getResources().openRawResource(
        R.raw.cdata_pass);
    BufferedReader reader = new BufferedReader(
        new InputStreamReader(inputStream));
    try {
      return reader.readLine();
    } catch (IOException e) {
      Utils.logError("readpassword: IOException!", e);
      return "";
    }
  }

  public static void saveBodyId(String bodyId, Context context) {
    // equals(), not ==: the id is parsed out of each response, so it is
    // never the same String object as the saved one, and == re-saved the
    // device on every screen that reported it.
    if (bodyId == null || "".equals(bodyId) || bodyId.equals(mTivoDevice.tsn)) {
      return;
    }

    mTivoDevice.tsn = bodyId;
    new Database(context).saveDevice(mTivoDevice);
  }

  public static void settingsError(
      final Activity activity, final int messageId) {
    settingsError(activity, messageId, Toast.LENGTH_SHORT);
  }

  public static void settingsError(
      final Activity activity, final int messageId, final int toastLen) {
    Utils.log("Settings error: " + activity.getResources().getString(messageId));
    activity.runOnUiThread(new Runnable() {
      public void run() {
        Utils.toast(activity, messageId, toastLen);
        Intent i;
        if (activity.getClass() == Discover.class) {
          i = new Intent(activity.getBaseContext(), Settings.class);
        } else {
          i = new Intent(activity.getBaseContext(), Discover.class);
        }
        activity.startActivity(i);
      }
    });
  }

  private static void stopThreads() {
    if (mInputThread != null) {
      mInputThread.mStopFlag = true;
      mInputThread.interrupt();
      mInputThread = null;
    }
    if (mOutputThread != null) {
      mOutputThread.mStopFlag = true;
      mOutputThread.interrupt();
      mOutputThread = null;
    }
  }
}
