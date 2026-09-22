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

package com.arantius.tivocommander.rpc;

import java.util.HashSet;
import java.util.Set;

import com.arantius.tivocommander.Utils;
import com.arantius.tivocommander.rpc.request.MindRpcRequest;
import com.arantius.tivocommander.rpc.response.MindRpcResponse;
import com.arantius.tivocommander.rpc.response.MindRpcResponseListener;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Fetch a whole "idSequence" list, however many pages it takes.
 *
 * The box hands back at most 1000 ids per answer, noLimit or not, and says
 * whether there are more with isBottom.  Asked once, a list longer than that
 * -- Recently Deleted on a busy box reaches it -- was silently cut off at the
 * 1000th entry.  Checked against a Bolt: 1017 deleted recordings came back as
 * a page of 1000 with isBottom false, then 17 at offset 1000 with isBottom
 * true, and no id in both.
 *
 * Not for recordingFolderItemSearch: that one ignores offset, answering the
 * same page again, and reports isBottom false even when it has sent all it
 * has.  A page that repeats ids already seen ends the fetch regardless.
 *
 * The listener hears once, as it did from the single request: either the
 * first error, or one idSequence carrying every id.
 */
public final class IdSequencePager {
  /** Makes a fresh request for one page; the pager fills in the offset. */
  public interface PageFactory {
    MindRpcRequest newPage();
  }

  /**
   * Stop after this many pages even if the box never says isBottom, so a box
   * that keeps answering "more" cannot keep this going forever.
   */
  static final int MAX_PAGES = 50;

  private final PageFactory mFactory;
  private final MindRpcResponseListener mListener;
  private final ArrayNode mIds = JsonNodeFactory.instance.arrayNode();
  private final Set<String> mSeen = new HashSet<String>();
  private int mPages = 0;

  private IdSequencePager(
      PageFactory factory, MindRpcResponseListener listener) {
    mFactory = factory;
    mListener = listener;
  }

  public static void fetchAll(
      PageFactory factory, MindRpcResponseListener listener) {
    new IdSequencePager(factory, listener).requestPage();
  }

  private void requestPage() {
    MindRpcRequest request = mFactory.newPage();
    if (mIds.size() > 0) {
      request.getDataMap().put("offset", mIds.size());
    }
    mPages++;
    MindRpc.addRequest(request, new MindRpcResponseListener() {
      public void onResponse(MindRpcResponse response) {
        onPage(response);
      }
    });
  }

  private void onPage(MindRpcResponse response) {
    if (Utils.isError(response)) {
      mListener.onResponse(response);
      return;
    }
    JsonNode body = response.getBody();
    JsonNode page = body.path("objectIdAndType");
    boolean more = !body.path("isBottom").asBoolean(true);
    if (mPages == 1 && !more) {
      // One page held it all: hand it on untouched.
      mListener.onResponse(response);
      return;
    }

    int added = 0;
    for (JsonNode id : page) {
      if (mSeen.add(id.asText())) {
        mIds.add(id);
        added++;
      }
    }
    // Nothing new means the box is not really paging; what we have is all.
    more = more && added > 0;
    if (more && mPages < MAX_PAGES) {
      requestPage();
      return;
    }
    if (more) {
      Utils.logError("IdSequencePager: gave up after " + mPages + " pages, "
          + mIds.size() + " ids");
    }

    ObjectNode whole = ((ObjectNode) body).deepCopy();
    whole.set("objectIdAndType", mIds);
    whole.put("isBottom", true);
    whole.put("isTop", true);
    mListener.onResponse(
        new MindRpcResponse(true, response.getRpcId(), whole));
  }
}
