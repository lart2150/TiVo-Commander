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

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Locale;

import android.util.Log;

import com.arantius.tivocommander.Utils;
import com.arantius.tivocommander.rpc.response.MindRpcResponse;
import com.arantius.tivocommander.rpc.response.MindRpcResponseFactory;

/**
 * Handle network level input. Generate appropriate response objects.
 */
public class MindRpcInput extends Thread {
  private static final String LOG_TAG = "tivo_commander";

  public volatile boolean mStopFlag = false;

  private final DataInputStream mStream;

  public MindRpcInput(DataInputStream mInputStream) {
    super("MindRpcInput");
    mStream = mInputStream;
  }

  @SuppressWarnings("deprecation")
  @Override
  public void run() {
    MindRpcResponseFactory mindRpcResponseFactory =
        new MindRpcResponseFactory();

    while (!mStopFlag) {
      try {
        // No sleep here: readLine() blocks until the box sends something, so
        // a pause per message only added 50ms to every answer.

        // Use deprecated readline on DataInputStream, because later I have to
        // read _bytes_ from it.
        String respLine = null;
        try {
          respLine = mStream.readLine();
        } catch (ArrayIndexOutOfBoundsException e) {
          break;
        }
        if (respLine == null) {
          // The socket has closed.
          break;
        }

        if (respLine.length() >= 6 && "MRPC/2".equals(respLine.substring(0, 6))) {
          String[] respBytes = respLine.split(" ");
          int headerLen = Integer.parseInt(respBytes[1]);
          int bodyLen = Integer.parseInt(respBytes[2]);

          // readFully(), not a loop over read(): read() answers -1 once the
          // socket closes, and adding that to a count never reaches the end,
          // so a box that went away mid-message spun this thread forever.
          byte[] headers = new byte[headerLen];
          mStream.readFully(headers);

          byte[] body = new byte[bodyLen];
          mStream.readFully(body);

          final MindRpcResponse response =
              mindRpcResponseFactory.create(headers, body);
          if (response != null) {
            Utils.log(String.format(Locale.US, "% 4d RECV %s", response.getRpcId(),
                response.getRespType()));
            Utils.logRpc(response.getBody());
            MindRpc.dispatchResponse(response);
          }
        }
      } catch (EOFException e) {
        Utils.log("MindRpcInput: socket closed mid-message.");
        break;
      } catch (IOException e) {
        if (!mStopFlag) {
          Log.e(LOG_TAG, "read: IOException!", e);
        }
        break;
      }
    }
    if (!mStopFlag) {
      // Nobody asked us to stop: the box hung up, or the socket was closed
      // under us because it stopped answering.
      MindRpc.connectionLost("input ended");
    }
  }
}
