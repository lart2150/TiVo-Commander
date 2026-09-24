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

import com.arantius.tivocommander.Utils;
import com.arantius.tivocommander.rpc.MindRpc;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Everything the box is going to record, as offer ids.
 *
 * This is what puts the "will record" mark in the guide.  A gridRowSearch will
 * not answer that question itself: "note": ["recordingForOfferId"] -- which
 * does work on the offerSearch behind the Upcoming screen -- is ignored there,
 * verified against an offer that was in fact scheduled.  So the To Do list is
 * read once and matched by offerId, which is exact.  (kmttg matches on title
 * and start time instead, which confuses two airings of the same program.)
 *
 * The recordingId comes back with it, which is what cancelling one needs.
 */
public class ScheduledOfferSearch extends MindRpcRequest {
  /** The box refuses a count much above this; 64 already fails. */
  public static final int PAGE_SIZE = 50;

  private static final JsonNode mResponseTemplate =
      Utils
          .parseJson("[{\"type\": \"responseTemplate\", \"fieldName\": [\"offerId\", \"recordingId\", \"state\"], \"typeName\": \"recording\"}]");

  public ScheduledOfferSearch(int offset) {
    super("recordingSearch");

    mDataMap.put("bodyId", MindRpc.mTivoDevice.tsn);
    mDataMap.put("levelOfDetail", "medium");
    mDataMap.put("state", new String[] { "inProgress", "scheduled" });
    mDataMap.put("count", PAGE_SIZE);
    mDataMap.put("offset", offset);
    mDataMap.put("responseTemplate", mResponseTemplate);
  }
}
