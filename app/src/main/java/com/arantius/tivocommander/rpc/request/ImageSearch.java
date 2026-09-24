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
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Artwork-only lookup for a collection or a piece of content.
 *
 * recordingSearch returns no image at any level of detail, so a screen showing
 * a recording has to ask for its artwork separately, by collection or content
 * id.  The response template is narrowed to just the image field, since that is
 * the only thing the caller wants back.
 */
public class ImageSearch extends BaseSearch {
  private static final String[] mNote = new String[] {};
  private static final JsonNode mResponseTemplate =
      Utils.parseJson("[{\"type\": \"responseTemplate\","
          + " \"fieldName\": [\"image\"], \"typeName\": \"collection\"},"
          + " {\"type\": \"responseTemplate\","
          + " \"fieldName\": [\"image\"], \"typeName\": \"content\"}]");

  public ImageSearch(String collectionId, String contentId) {
    super(collectionId, contentId);
    addCommon(mNote, mResponseTemplate);
  }
}
