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

package com.arantius.tivocommander.stream;

import com.arantius.tivocommander.TivoModel;
import com.arantius.tivocommander.Utils;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Asks a TiVo whether it will stream, from /sysinfo/json/svcinfo on port 49152.
 * The authority on in-app playback -- TivoModel.hasTranscoder is only the hint
 * deciding whether to offer the button.  Blocking.
 */
public final class StreamProbe {
  public enum State {
    READY,
    UNAVAILABLE,
    RETRY_LATER
  }

  public final State state;
  public final String tsn;
  public final String detail;
  public final int clientsInUse;
  public final int clientsMax;

  private StreamProbe(
      State state, String tsn, String detail, int clientsInUse, int clientsMax) {
    this.state = state;
    this.tsn = tsn;
    this.detail = detail;
    this.clientsInUse = clientsInUse;
    this.clientsMax = clientsMax;
  }

  public boolean isReady() {
    return state == State.READY;
  }

  public TivoModel model() {
    return TivoModel.forTsn(tsn);
  }

  public static StreamProbe probe(String addr) {
    if (addr == null || "".equals(addr)) {
      return new StreamProbe(
          State.UNAVAILABLE, null, "no address configured", -1, -1);
    }

    StreamHttp.Response rsp =
        StreamHttp.get(StreamHttp.baseUrl(addr) + "/sysinfo/json/svcinfo");
    if (rsp == null) {
      // On a box with no transcoder this is permanent, but it is
      // indistinguishable from one that is off or off-network, so it stays
      // retryable and the TSN carries the "this model never will" half.
      return new StreamProbe(State.RETRY_LATER, null,
          "the TiVo did not answer on port " + StreamHttp.PORT, -1, -1);
    }
    if (!rsp.ok()) {
      return new StreamProbe(State.RETRY_LATER, null, rsp.describe(), -1, -1);
    }

    JsonNode info = rsp.json();
    if (info == null) {
      return new StreamProbe(State.RETRY_LATER, null,
          "could not read the TiVo's streaming status", -1, -1);
    }

    final String tsn = info.path("sg").asText(null);
    final int inUse = info.path("svcStreamingClients").path("Num").asInt(-1);
    final int max = info.path("svcStreamingClients").path("Max").asInt(-1);

    if (info.path("ServiceStreamingAllowed").asInt(0) != 1) {
      return new StreamProbe(State.UNAVAILABLE, tsn,
          "streaming is not enabled on this TiVo", inUse, max);
    }

    final int state = info.path("svcStreamingStateExt").asInt(-1);
    if (state == TranscoderStatus.STATE_READY) {
      return new StreamProbe(State.READY, tsn, "ready", inUse, max);
    }
    if (state == TranscoderStatus.STATE_DISABLED) {
      return new StreamProbe(State.UNAVAILABLE, tsn,
          "streaming is disabled on this TiVo", inUse, max);
    }
    Utils.log("StreamProbe: " + addr + " is "
        + TranscoderStatus.stateName(state));
    return new StreamProbe(State.RETRY_LATER, tsn,
        "the TiVo is not ready to stream ("
            + TranscoderStatus.stateName(state) + ")", inUse, max);
  }
}
