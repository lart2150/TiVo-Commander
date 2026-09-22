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

import com.arantius.tivocommander.Utils;
import com.arantius.tivocommander.rpc.MindRpc;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Everything the box has decided not to record.
 *
 * Same recordingSearch the To Do list uses, asked for the cancelled state
 * instead.  What makes it worth a screen of its own is "cancellationReason",
 * which is the box's own answer to "why isn't this recording?" -- a conflict
 * with something else, or simply that someone cancelled it.
 */
public class CancelledSearch extends MindRpcRequest {
  /** The box refuses a count much above this; 64 already fails. */
  public static final int PAGE_SIZE = 50;

  private static final JsonNode mResponseTemplate =
      Utils
          .parseJson("[{\"type\": \"responseTemplate\", \"fieldName\": [\"title\", \"subtitle\", \"startTime\", \"duration\", \"cancellationReason\", \"offerId\", \"contentId\", \"collectionId\", \"recordingId\", \"state\", \"channel\", \"seasonNumber\", \"episodeNum\", \"episodic\"], \"typeName\": \"recording\"}, {\"type\": \"responseTemplate\", \"fieldName\": [\"channelNumber\", \"callSign\", \"sourceType\"], \"typeName\": \"channel\"}]");

  public CancelledSearch(int offset) {
    super("recordingSearch");

    mDataMap.put("bodyId", MindRpc.mTivoDevice.tsn);
    mDataMap.put("levelOfDetail", "medium");
    mDataMap.put("state", new String[] { "cancelled" });
    mDataMap.put("count", PAGE_SIZE);
    mDataMap.put("offset", offset);
    mDataMap.put("responseTemplate", mResponseTemplate);
  }
}
