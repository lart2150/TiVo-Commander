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

package com.arantius.tivocommander.rpc.request;

import java.util.HashMap;
import java.util.Map;

import com.arantius.tivocommander.rpc.MindRpc;

/**
 * Start transcoding a recording as HLS; the response carries
 * hlsSession.playlistUri (a path on port 49152) and hlsSessionId, and the box
 * answers on the ordinary local connection -- TiVo's cloud is not involved.
 * The schema is raised for this request alone: these types do not exist at
 * schema 7, and raising it globally would change every other screen.
 */
public class HlsStreamRecordingRequest extends MindRpcRequest {
  private static final int STREAMING_SCHEMA_VERSION = 17;

  public HlsStreamRecordingRequest(String recordingId, String clientUuid) {
    super("hlsStreamRecordingRequest");
    mSchemaVersion = STREAMING_SCHEMA_VERSION;

    final Map<String, Object> deviceConfiguration =
        new HashMap<String, Object>();
    deviceConfiguration.put("type", "deviceConfiguration");
    deviceConfiguration.put("deviceType", "webPlayer");

    final Map<String, Object> encryption = new HashMap<String, Object>();
    encryption.put("type", "hlsStreamEncryptionInfo");
    encryption.put("encryptionType", "hlsAes128Cbc");

    mDataMap.put("bodyId", MindRpc.mTivoDevice.tsn);
    mDataMap.put("recordingId", recordingId);
    mDataMap.put("clientUuid", clientUuid);
    mDataMap.put("deviceConfiguration", deviceConfiguration);
    mDataMap.put("sessionType", "streaming");
    mDataMap.put("hlsStreamDesiredVariantsSet", "ABR");
    mDataMap.put("supportedEncryption", encryption);
    mDataMap.put("isLocal", true);
  }
}
