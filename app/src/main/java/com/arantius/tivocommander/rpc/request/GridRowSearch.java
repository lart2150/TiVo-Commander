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

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import com.arantius.tivocommander.Utils;
import com.arantius.tivocommander.rpc.MindRpc;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * A page of the guide: several channels' listings over a span of time.
 *
 * The box answers with one "gridRow" per channel, each carrying the offers
 * that overlap the span.  Everything the grid needs comes from this one
 * request -- there is no separate channel lineup call, because each row names
 * its own channel and "isReceived" drops the ones the box cannot tune.
 *
 * Paging is by anchor, not by offset: the anchor names where the page starts
 * and "count" says how many channels follow it.  Sending no anchor at all
 * starts at the top of the lineup.  The anchor channel is itself returned as
 * the first row, so a page after the first repeats the previous page's last
 * row -- {@link com.arantius.tivocommander.Guide} drops it.
 *
 * The two times bound an overlap rather than a containment: an offer counts
 * when it ends after the span starts (minEndTime) and starts before the span
 * ends (maxStartTime), so a program already running at the left edge comes
 * back too and a row has no hole at its start.
 *
 * The response template matters more here than elsewhere -- without it the box
 * sends every field it has, including a copy of the channel inside each offer,
 * and a 20 channel by 4 hour page runs 268KB instead of 87KB.
 */
public class GridRowSearch extends MindRpcRequest {
  /** Channels per request.  The box sets no low ceiling; this bounds latency. */
  public static final int PAGE_SIZE = 20;

  private static final JsonNode mResponseTemplate =
      Utils
          .parseJson("[{\"type\": \"responseTemplate\", \"fieldName\": [\"gridRow\"], \"typeName\": \"gridRowList\"}, {\"type\": \"responseTemplate\", \"fieldName\": [\"channel\", \"offer\"], \"typeName\": \"gridRow\"}, {\"type\": \"responseTemplate\", \"fieldName\": [\"title\", \"subtitle\", \"startTime\", \"duration\", \"contentId\", \"collectionId\", \"offerId\", \"episodic\", \"seasonNumber\", \"episodeNum\", \"isEpisode\", \"isNew\", \"repeat\", \"hdtv\", \"description\", \"collectionType\", \"originalAirdate\", \"movieYear\"], \"typeName\": \"offer\"}, {\"type\": \"responseTemplate\", \"fieldName\": [\"channelNumber\", \"sourceType\", \"callSign\", \"name\", \"stationId\", \"channelId\"], \"typeName\": \"channel\"}]");

  /**
   * @param anchor The channel the page starts at, or null to start at the top
   *     of the lineup.
   */
  public GridRowSearch(JsonNode anchor, Date spanStart, Date spanEnd) {
    super("gridRowSearch");

    mDataMap.put("bodyId", MindRpc.mTivoDevice.tsn);
    mDataMap.put("levelOfDetail", "medium");
    mDataMap.put("orderBy", new String[] { "channelNumber" });
    // A string, not a boolean: this is how every other isReceived in this app
    // and in kmttg goes out, and the box accepts either.
    mDataMap.put("isReceived", "true");
    mDataMap.put("count", PAGE_SIZE);
    mDataMap.put("minEndTime", Utils.formatDateTimeStr(spanStart));
    mDataMap.put("maxStartTime", Utils.formatDateTimeStr(spanEnd));
    mDataMap.put("responseTemplate", mResponseTemplate);

    if (anchor != null) {
      Map<String, Object> id = new HashMap<String, Object>();
      id.put("type", "channelIdentifier");
      id.put("channelNumber", anchor.path("channelNumber").asText());
      id.put("sourceType", anchor.path("sourceType").asText());
      // Two channels can share a number across sources, so pass the ids the
      // row came with to name exactly the one meant.
      if (anchor.hasNonNull("stationId")) {
        id.put("stationId", anchor.path("stationId").asText());
      }
      if (anchor.hasNonNull("channelId")) {
        id.put("channelId", anchor.path("channelId").asText());
      }
      mDataMap.put("anchorChannelIdentifier", id);
    }
  }
}
