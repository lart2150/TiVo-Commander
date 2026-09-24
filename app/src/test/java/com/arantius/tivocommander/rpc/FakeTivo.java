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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.arantius.tivocommander.Device;
import com.arantius.tivocommander.rpc.request.MindRpcRequest;
import com.arantius.tivocommander.rpc.response.MindRpcResponse;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * A TiVo made of recorded responses, for driving screens in a test.
 *
 * Install one and MindRpc reports itself connected and hands requests here
 * instead of to a socket, so a screen's onCreate runs the whole way through
 * rather than bouncing to the Connect screen.  Answers are queued rather than
 * returned inline, because on a real connection they arrive later, from the
 * reader thread -- a listener that ran inside addRequest() would see a screen
 * only half set up.  Call {@link #deliver()} when the screen is ready.
 */
public class FakeTivo implements MindRpcTransport {
  /** An answer waiting to be handed back. */
  private static class Pending {
    final int rpcId;
    final JsonNode body;

    Pending(int rpcId, JsonNode body) {
      this.rpcId = rpcId;
      this.body = body;
    }
  }

  private final Map<String, Deque<JsonNode>> mAnswers =
      new HashMap<String, Deque<JsonNode>>();
  private final List<MindRpcRequest> mSent = new ArrayList<MindRpcRequest>();
  private final List<Pending> mPending = new ArrayList<Pending>();

  /** Stand in for the TiVo, with a device configured, until uninstalled. */
  public static FakeTivo install() {
    Device device = new Device();
    device.tsn = "tsn:0000000000000000";
    device.mak = "0000000000";
    device.addr = "0.0.0.0";
    device.device_name = "Fake";
    MindRpc.mTivoDevice = device;
    MindRpc.mBodyIsAuthed = true;

    FakeTivo fake = new FakeTivo();
    MindRpc.setTransport(fake);
    return fake;
  }

  public static void uninstall() {
    MindRpc.setTransport(null);
    MindRpc.mBodyIsAuthed = false;
  }

  /**
   * Answer a request of this type with this body.
   *
   * Call it more than once for the same type to answer successive requests
   * differently -- My Shows sends two recordingFolderItemSearches, the first
   * for a list of ids and the second for the details of those ids.  The last
   * answer given is reused once the earlier ones are spent.
   */
  public FakeTivo answer(String reqType, JsonNode body) {
    Deque<JsonNode> queued = mAnswers.get(reqType);
    if (queued == null) {
      queued = new ArrayDeque<JsonNode>();
      mAnswers.put(reqType, queued);
    }
    queued.add(body);
    return this;
  }

  public void send(MindRpcRequest request) {
    mSent.add(request);
    Deque<JsonNode> queued = mAnswers.get(request.getReqType());
    if (queued == null || queued.isEmpty()) {
      // Nothing configured: the request simply goes unanswered, which is what
      // a screen sees when the box ignores it.
      return;
    }
    JsonNode body = queued.size() > 1 ? queued.removeFirst() : queued.peek();
    mPending.add(new Pending(request.getRpcId(), body));
  }

  /**
   * Hand back every answer owed, including any owed by requests those answers
   * set off in turn.
   */
  public void deliver() {
    for (int round = 0; round < 20 && !mPending.isEmpty(); round++) {
      List<Pending> batch = new ArrayList<Pending>(mPending);
      mPending.clear();
      for (Pending pending : batch) {
        MindRpc.dispatchResponse(
            new MindRpcResponse(true, pending.rpcId, pending.body));
      }
    }
    if (!mPending.isEmpty()) {
      throw new AssertionError("responses are still triggering requests after "
          + "20 rounds; something is looping");
    }
  }

  /** Every request the screen has made, in order. */
  public List<MindRpcRequest> sent() {
    return mSent;
  }

  /** The request types the screen has made, in order. */
  public List<String> sentTypes() {
    List<String> types = new ArrayList<String>();
    for (MindRpcRequest request : mSent) {
      types.add(request.getReqType());
    }
    return types;
  }

  /** The first request of a type, or null if the screen never made one. */
  public MindRpcRequest firstOfType(String reqType) {
    for (MindRpcRequest request : mSent) {
      if (reqType.equals(request.getReqType())) {
        return request;
      }
    }
    return null;
  }

  public void clearSent() {
    mSent.clear();
  }
}
