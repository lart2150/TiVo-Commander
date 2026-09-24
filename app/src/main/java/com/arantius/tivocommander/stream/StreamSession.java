/*
DVR Commander allows control of a TiVo Premiere device.
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

package com.arantius.tivocommander.stream;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.preference.PreferenceManager;

import com.arantius.tivocommander.Device;
import com.arantius.tivocommander.TivoModel;
import com.arantius.tivocommander.Utils;
import com.arantius.tivocommander.rpc.MindRpc;
import com.arantius.tivocommander.rpc.request.HlsStreamExtend;
import com.arantius.tivocommander.rpc.request.HlsStreamRecordingRequest;
import com.arantius.tivocommander.rpc.request.HlsStreamRelease;
import com.arantius.tivocommander.rpc.response.MindRpcResponse;
import com.arantius.tivocommander.rpc.response.MindRpcResponseListener;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * One in-app playback session: take a transcoder, hand back a playlist URL,
 * give the transcoder up again.  The lifetime rule is the whole job -- a TiVo
 * has very few transcoders and expires none, so an unreleased session is lost
 * until the box restarts, and a killed process releases nothing.  So
 * {@link #close} is owed on every exit path, is idempotent, and releases on a
 * process-wide executor; and {@link #clientUuid} is derived from the TSN, so
 * it survives crashes and lets reclaimOrphans reclaim only our own leftovers.
 */
public class StreamSession {
  public interface Callback {
    void onStreamReady(String playlistUrl);

    void onStreamFailed(String message, boolean retryable);
  }

  private static final String UUID_NAMESPACE = "tivo-commander-stream";

  /** Survives process death, so the uuid derived from it does too. */
  private static final String PREF_INSTALL_ID = "stream_install_id";

  private static final long ORPHAN_AGE_MS = 60000;

  private static final long RELEASE_GRACE_MS = 3000;

  /**
   * How long to wait for the box to answer a create.  MindRpc.addRequest drops
   * a request whose socket has gone, without queueing it or registering the
   * listener, so without this the screen waits for an answer that can never
   * arrive.  The box answers in a few seconds when it is going to.
   */
  private static final long CREATE_TIMEOUT_MS = 30000;

  private static final long EXTEND_INTERVAL_MS = 60000;

  private static final Pattern DIGITS = Pattern.compile("\\d+");

  private static final ExecutorService EXECUTOR =
      Executors.newSingleThreadExecutor();

  /**
   * The one session this app may have open at a time: two readers at different
   * positions make the transcoder seek back and forth.  Player to Player runs
   * onStart before onStop, so the overlap is normal, not a rare race.
   */
  private static final AtomicReference<StreamSession> LIVE =
      new AtomicReference<StreamSession>();

  private static final long HANDOVER_TIMEOUT_MS = 5000;
  private static final long HANDOVER_POLL_MS = 250;

  private final Handler mMainHandler = new Handler(Looper.getMainLooper());
  private final Device mDevice;
  private final String mInstallId;
  private final AtomicBoolean mClosed = new AtomicBoolean(false);

  private volatile String mSessionId;

  private final Runnable mExtend = new Runnable() {
    public void run() {
      final String sessionId = mSessionId;
      if (sessionId == null) {
        return;
      }
      MindRpc.addRequest(new HlsStreamExtend(sessionId), null);
      mMainHandler.postDelayed(this, EXTEND_INTERVAL_MS);
    }
  };

  public StreamSession(Context context, Device device) {
    mDevice = device;
    mInstallId = installId(context);
  }

  /**
   * A random id, made once and kept.
   *
   * The uuid below has to survive a crash, but it must not be the same on two
   * phones: it is what authorises reclaiming a session, and a value derived
   * from the TiVo alone would let one phone tear down another's playback.
   */
  private static synchronized String installId(Context context) {
    final SharedPreferences prefs =
        PreferenceManager.getDefaultSharedPreferences(context);
    String id = prefs.getString(PREF_INSTALL_ID, null);
    if (id == null) {
      id = UUID.randomUUID().toString();
      prefs.edit().putString(PREF_INSTALL_ID, id).apply();
    }
    return id;
  }

  /** Playing keeps the transcoder alive, so the timer runs only while paused. */
  public void setPaused(boolean paused) {
    mMainHandler.removeCallbacks(mExtend);
    if (paused && mSessionId != null) {
      mMainHandler.postDelayed(mExtend, EXTEND_INTERVAL_MS);
    }
  }

  static String clientUuid(String installId, String tsn) {
    return UUID.nameUUIDFromBytes(
        (UUID_NAMESPACE + ":" + installId + ":" + tsn)
            .getBytes(StandardCharsets.UTF_8))
        .toString();
  }

  private String clientUuid() {
    return clientUuid(mInstallId, mDevice == null ? "" : mDevice.tsn);
  }

  public static boolean looksSupported(Device device) {
    return device != null && TivoModel.hasTranscoder(device.tsn);
  }

  public void open(final String recordingId, final Callback callback) {
    if (mDevice == null || mDevice.addr == null) {
      // Reachable after process death: MindRpc.mTivoDevice is static, so a
      // restored activity can run before anything has set it.
      fail(callback, "No DVR is configured.", false);
      return;
    }

    final StreamSession previous = LIVE.getAndSet(this);
    final boolean handingOver = previous != null && previous != this;
    if (handingOver) {
      previous.close();
    }

    EXECUTOR.execute(new Runnable() {
      public void run() {
        if (handingOver) {
          awaitHandover();
        }
        final StreamProbe probe = StreamProbe.probe(mDevice.addr);
        if (!probe.isReady()) {
          final TivoModel model = TivoModel.forTsn(mDevice.tsn);
          final String message =
              model != null && !model.hasTranscoder
                  ? "A TiVo " + model.name + " has no built-in transcoder, so "
                      + "it cannot stream to this device."
                  : "Cannot stream: " + probe.detail + ".";
          fail(callback, message,
              probe.state == StreamProbe.State.RETRY_LATER);
          return;
        }

        // Leftovers hold a transcoder we are about to ask for.
        reclaimOrphans();

        requestSession(recordingId, callback);
      }
    });
  }

  /** Bounded wait for the outgoing session: a clear failure beats a hang. */
  private void awaitHandover() {
    final String uuid = clientUuid();
    final long deadline = System.currentTimeMillis() + HANDOVER_TIMEOUT_MS;
    while (System.currentTimeMillis() < deadline) {
      final JsonNode clients = clientList();
      if (clients == null) {
        return;
      }
      boolean ours = false;
      for (JsonNode client : clients) {
        if (uuid.equals(client.path("uuid").asText(""))) {
          ours = true;
          break;
        }
      }
      if (!ours) {
        return;
      }
      try {
        Thread.sleep(HANDOVER_POLL_MS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
    Utils.log("StreamSession: previous session still listed; continuing");
  }

  public void close() {
    LIVE.compareAndSet(this, null);
    mMainHandler.removeCallbacks(mExtend);
    if (!mClosed.compareAndSet(false, true)) {
      return;
    }
    final String sessionId = mSessionId;
    if (sessionId == null) {
      return;
    }
    mSessionId = null;

    final String uuid = clientUuid();
    final AtomicBoolean verified = new AtomicBoolean(false);
    final Runnable verify = new Runnable() {
      public void run() {
        if (!verified.compareAndSet(false, true)) {
          return;
        }
        EXECUTOR.execute(new Runnable() {
          public void run() {
            final String id = numericId(sessionId);
            if (confirmedGone(uuid, id)) {
              return;
            }
            if ("".equals(id)) {
              // Saying "forcing" and then doing nothing would hide a held
              // transcoder behind a reassuring line in the log.
              Utils.log("StreamSession: release unconfirmed and " + sessionId
                  + " has no numeric id, so it cannot be forced");
              return;
            }
            Utils.log("StreamSession: RPC release did not take; forcing "
                + sessionId);
            forceRelease(id);
          }
        });
      }
    };

    // The check must wait for the answer: MindRpc.addRequest only queues onto
    // the output thread, so checking straight after races the request out of
    // the door and forces on every close.
    MindRpc.addRequest(new HlsStreamRelease(sessionId, uuid),
        new MindRpcResponseListener() {
          public void onResponse(MindRpcResponse response) {
            verify.run();
          }
        });

    mMainHandler.postDelayed(verify, RELEASE_GRACE_MS);
  }

  private void requestSession(String recordingId, final Callback callback) {
    final String uuid = clientUuid();
    final AtomicBoolean answered = new AtomicBoolean(false);

    mMainHandler.postDelayed(new Runnable() {
      public void run() {
        if (!answered.compareAndSet(false, true)) {
          return;
        }
        Utils.log("StreamSession: no answer to the stream request");
        callback.onStreamFailed(
            "The DVR did not answer.  Check the connection and try again.",
            true);
      }
    }, CREATE_TIMEOUT_MS);

    MindRpc.addRequest(
        new HlsStreamRecordingRequest(recordingId, uuid),
        new MindRpcResponseListener() {
          public void onResponse(MindRpcResponse response) {
            if (!answered.compareAndSet(false, true)) {
              return;
            }
            final JsonNode body = response.getBody();
            final JsonNode session = body.path("hlsSession");
            final String playlistUri = session.path("playlistUri").asText(null);
            final String sessionId = session.path("hlsSessionId").asText(null);

            if (playlistUri == null || sessionId == null) {
              onCreateRefused(body, callback);
              return;
            }

            mSessionId = sessionId;
            if (mClosed.get()) {
              // Screen went away mid-request; close() would no-op, so re-arm it.
              mClosed.set(false);
              close();
              return;
            }
            callback.onStreamReady(
                StreamHttp.baseUrl(mDevice.addr) + playlistUri);
          }
        });
  }

  private void onCreateRefused(JsonNode body, Callback callback) {
    String code = body.path("errorCode").asText("");
    if ("".equals(code)) {
      code = body.path("cause").path("code").asText("");
    }
    if ("".equals(code)) {
      code = body.path("code").asText("unknown");
    }
    Utils.log("StreamSession: could not start a stream -- " + code + " / "
        + body.path("text").asText(""));

    if ("maxSessionsExceeded".equals(code)) {
      callback.onStreamFailed(
          "The DVR has no free transcoder right now.  Try again in a moment.",
          true);
      return;
    }
    callback.onStreamFailed(
        "The DVR would not start a stream (" + code + ").", true);
  }

  private void reclaimOrphans() {
    final String uuid = clientUuid();
    final JsonNode clients = clientList();
    if (clients == null) {
      return;
    }
    final long now = System.currentTimeMillis();
    for (JsonNode client : clients) {
      if (!uuid.equals(client.path("uuid").asText(""))) {
        continue;
      }
      final long last = client.path("tLastReq").asLong(0);
      if (last > 0 && now - last < ORPHAN_AGE_MS) {
        continue;
      }
      final long id = client.path("sessionId").asLong(0);
      if (id <= 0) {
        continue;
      }
      Utils.log("StreamSession: reclaiming orphaned session " + id);
      forceRelease(String.valueOf(id));
    }
  }

  private boolean confirmedGone(String uuid, String id) {
    if ("".equals(id)) {
      return false;
    }
    final JsonNode clients = clientList();
    if (clients == null) {
      return false;
    }
    for (JsonNode client : clients) {
      if (id.equals(String.valueOf(client.path("sessionId").asLong(0)))
          && uuid.equals(client.path("uuid").asText(""))) {
        return false;
      }
    }
    return true;
  }

  private JsonNode clientList() {
    final StreamHttp.Response rsp = StreamHttp.get(
        StreamHttp.baseUrl(mDevice.addr) + "/sysinfo/json/clients");
    if (rsp == null || !rsp.ok() || rsp.json() == null) {
      return null;
    }
    final JsonNode clients = rsp.json().path("clients");
    return clients.isArray() ? clients : null;
  }

  /**
   * The only way to clear an orphan left by a dead process.  Nothing but the id
   * varies: neighbouring actions on this endpoint drop every client on the box.
   */
  private void forceRelease(String id) {
    if ("".equals(id) || "0".equals(id)) {
      return;
    }
    final StreamHttp.Response rsp = StreamHttp.get(
        StreamHttp.baseUrl(mDevice.addr)
            + "/sysinfo/control?config=session&id=" + id + "&action=release");
    if (rsp == null || !rsp.ok()) {
      Utils.log("StreamSession: could not release session " + id
          + "; it may hold a transcoder until the TiVo restarts");
    }
  }

  static String numericId(String sessionId) {
    if (sessionId == null) {
      return "";
    }
    final int dot = sessionId.lastIndexOf('.');
    final String tail = dot < 0 ? sessionId : sessionId.substring(dot + 1);
    return DIGITS.matcher(tail).matches() ? tail : "";
  }

  private void fail(
      final Callback callback, final String message, final boolean retryable) {
    mMainHandler.post(new Runnable() {
      public void run() {
        callback.onStreamFailed(message, retryable);
      }
    });
  }

  @Override
  public String toString() {
    return String.format(Locale.US, "StreamSession[%s, session=%s]",
        mDevice == null ? "?" : mDevice.addr, mSessionId);
  }
}
