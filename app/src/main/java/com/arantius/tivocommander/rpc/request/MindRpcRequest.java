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

package com.arantius.tivocommander.rpc.request;

import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import com.arantius.tivocommander.Utils;
import com.arantius.tivocommander.rpc.MindRpc;

public abstract class MindRpcRequest {
  /**
   * The schema every request is sent at: what kmttg sends first.
   *
   * The schema also picks the shape of the *responses*.  Everything this app
   * reads was written against 7, so the move to 17 was checked by replaying
   * each of its searches against a Bolt at both and diffing the answers:
   * every field 7 returned still comes back, with the same type and value,
   * and 17 only adds (networkInterface, percentWatched, isNew, the OnePass
   * options ...).  The one thing 17 drops is the stand-in "All channels"
   * channel on a OnePass that records from any channel; it had no stationId
   * or logo, so nothing that matched on those changes.
   */
  public static final int SCHEMA_VERSION = 17;

  /**
   * The schema to fall back to for a box too old for 17, as kmttg does.
   *
   * It is the newest one older TiVo software is known to take.
   */
  public static final int SCHEMA_VERSION_OLD = 14;

  /** How long to wait for a first answer before calling the link dead. */
  public static final long RESPONSE_TIMEOUT_MS = 30 * 1000L;

  /**
   * Set once a box has answered "Unsupported schema version", and from then
   * on every request is sent at the old schema.  It is per box, so it is
   * cleared on every new connection.
   */
  private static volatile boolean sUseOldSchema = false;

  private String mReqType;

  protected Map<String, Object> mDataMap = new HashMap<String, Object>();
  protected String mResponseCount = "single";
  protected int mRpcId;
  protected int mSessionId = 0;

  public MindRpcRequest(String type) {
    mRpcId = MindRpc.getRpcId();
    mSessionId = MindRpc.getSessionId();
    setReqType(type);
  }

  public Map<String, Object> getDataMap() {
    return mDataMap;
  }

  public String getDataString() {
    String data = Utils.stringifyToJson(mDataMap);
    if (data == null) {
      Utils.logError("Stringify failure; request body");
    }
    return data;
  }

  public String getReqType() {
    return mReqType;
  }

  public int getRpcId() {
    return mRpcId;
  }

  /** The schema a request written now goes out at. */
  public static int getSchemaVersion() {
    return sUseOldSchema ? SCHEMA_VERSION_OLD : SCHEMA_VERSION;
  }

  /**
   * Note that the box refused the current schema.
   *
   * @return Whether this changed anything, i.e. whether a request refused
   *     for its schema is worth sending again.
   */
  public static boolean fallBackToOldSchema() {
    if (sUseOldSchema) {
      return false;
    }
    sUseOldSchema = true;
    return true;
  }

  /** A new connection may be to a different box; start from the newest. */
  public static void resetSchemaVersion() {
    sUseOldSchema = false;
  }

  /**
   * How long the box may take to answer this before the link is taken to be
   * dead.  Long enough that nothing seen in practice comes near it: the
   * slowest answer in kmttg's own log of this box took under 4 seconds.
   */
  public long getResponseTimeoutMs() {
    return RESPONSE_TIMEOUT_MS;
  }

  /**
   * Whether the box answers this at all.  Only requests that are answered can
   * be timed; everything but a cancel is.
   */
  public boolean expectsResponse() {
    return true;
  }

  public void setLevelOfDetail(String levelOfDetail) {
    mDataMap.put("levelOfDetail", levelOfDetail);
  }

  public void setReqType(String type) {
    mReqType = type;
    mDataMap.put("type", mReqType);
  }

  /**
   * Convert the request into a well formatted byte array for the network.
   * @throws UnsupportedEncodingException
   */
  public byte[] getBytes() throws UnsupportedEncodingException {
    // @formatter:off
    String headers = Utils.join("\r\n",
        "Type: request",
        "RpcId: " + getRpcId(),
        "SchemaVersion: " + getSchemaVersion(),
        "Content-Type: application/json",
        "RequestType: " + mReqType,
        "ResponseCount: " + mResponseCount,
        "BodyId: " + MindRpc.mTivoDevice.tsn,
        "X-ApplicationName: Quicksilver ",
        "X-ApplicationVersion: 1.2 ",
        String.format("X-ApplicationSessionId: 0x%x", mSessionId));
    // @formatter:on
    String body = getDataString();

    // NOTE: The lengths here must be the length in *bytes*, not characters!
    // Thus all the .getBytes() conversions to find the proper lengths.
    // "+ 2" is the "\r\n" we'll add next.
    String reqLine =
        String.format(Locale.US, "MRPC/2 %d %d",
            headers.getBytes("UTF-8").length + 2,
            body.getBytes("UTF-8").length);
    String request = Utils.join("\r\n", reqLine, headers, body);
    byte[] requestBytes = request.getBytes("UTF-8");
    return requestBytes;
  }
}
