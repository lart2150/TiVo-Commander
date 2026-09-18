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
import com.fasterxml.jackson.databind.JsonNode;

public class BaseSearch extends MindRpcRequest {
  public BaseSearch(String collectionId, String contentId) {
    super(""); // We'll figure out type next.

    if (collectionId != null) {
      setReqType("collectionSearch");
      mDataMap.put("collectionId", new String[] { collectionId });
      mDataMap.put("filterUnavailable", false);
    } else if (contentId != null) {
      setReqType("contentSearch");
      mDataMap.put("contentId", new String[] { contentId });
      mDataMap.put("filterUnavailableContent", false);
    }

    mDataMap.put("bodyId", MindRpc.mTivoDevice.tsn);
    mDataMap.put("levelOfDetail", "high");
  }

  /**
   * No imageRuleset is sent.
   *
   * These searches used to carry rulesets of "exactMatchDimension" rules at
   * the pixel sizes the 2011 UI wanted (139x104, 113x150, ...).  Asking for an
   * exact size now yields no match, and the service answers by leaving the
   * image field out of the response altogether, which is why artwork stopped
   * appearing everywhere.  Asking without a ruleset returns every size it has
   * (70x53 up to 360x270 for a typical series) and Utils.findImageUrl() picks
   * among them -- TiVo's own host first, largest within that.
   */
  protected void addCommon(String[] note, JsonNode responseTemplate) {
    mDataMap.put("note", note);
    mDataMap.put("responseTemplate", responseTemplate);
  }
}
