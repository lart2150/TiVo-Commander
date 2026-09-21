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

import com.arantius.tivocommander.rpc.MindRpc;

/**
 * Give back the transcoder a {@link HlsStreamRecordingRequest} took.  Nothing
 * on the box expires a session, and this can fail silently.
 */
public class HlsStreamRelease extends MindRpcRequest {
  private static final int STREAMING_SCHEMA_VERSION = 17;

  public HlsStreamRelease(String hlsSessionId, String clientUuid) {
    super("hlsStreamRelease");
    mSchemaVersion = STREAMING_SCHEMA_VERSION;

    mDataMap.put("bodyId", MindRpc.mTivoDevice.tsn);
    mDataMap.put("clientUuid", clientUuid);
    mDataMap.put("hlsSessionId", hlsSessionId);
  }
}
