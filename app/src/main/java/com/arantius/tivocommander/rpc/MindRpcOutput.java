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

package com.arantius.tivocommander.rpc;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import android.util.Log;

import com.arantius.tivocommander.Utils;
import com.arantius.tivocommander.rpc.request.MindRpcRequest;

/**
 * Handle network level output. Accept request objects.
 */
public class MindRpcOutput extends Thread {
  private static final String LOG_TAG = "tivo_commander";

  /**
   * How long to wait on an empty queue before looking at the clock.  Queued
   * requests wake the thread at once; this only bounds how late it notices a
   * stop, or an answer that is overdue.
   */
  private static final long IDLE_POLL_MS = 1000;

  public volatile boolean mStopFlag = false;
  private final LinkedBlockingQueue<MindRpcRequest> mRequestQueue =
      new LinkedBlockingQueue<MindRpcRequest>();
  private final DataOutputStream mStream;

  public MindRpcOutput(DataOutputStream mOutputStream) {
    super("MindRpcOutput");
    mStream = mOutputStream;
  }

  public void addRequest(MindRpcRequest request) {
    mRequestQueue.add(request);
  }

  @Override
  public void run() {
    while (!mStopFlag) {
      try {
        // Waits for work rather than sleeping 50ms between looks, which made
        // every request at least that late and a batch of them crawl.
        MindRpcRequest request =
            mRequestQueue.poll(IDLE_POLL_MS, TimeUnit.MILLISECONDS);
        if (request != null) {
          Utils.log(String.format(Locale.US, "% 4d CALL %s",
              request.getRpcId(),
              request.getReqType()));
          Utils.logRpc(request.getDataMap());
          byte[] requestBytes = request.getBytes();
          mStream.write(requestBytes, 0, requestBytes.length);
          mStream.flush();
          MindRpc.requestSent(request);
        }
        MindRpc.checkOverdue();
      } catch (InterruptedException e) {
        Utils.log("MindRpcOutput: thread interrupted.");
        break;
      } catch (IOException e) {
        if (!mStopFlag) {
          Log.e(LOG_TAG, "write: io exception!", e);
          MindRpc.connectionLost("write failed");
        }
        break;
      }
    }
  }
}
