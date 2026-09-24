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

package com.arantius.tivocommander;

import java.util.HashMap;
import java.util.Map;

import android.content.Context;
import android.view.View;
import android.widget.ImageView;

import com.arantius.tivocommander.rpc.MindRpc;
import com.arantius.tivocommander.rpc.request.ImageSearch;
import com.arantius.tivocommander.rpc.response.MindRpcResponse;
import com.arantius.tivocommander.rpc.response.MindRpcResponseListener;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Puts artwork on a row whose own search result did not carry any.
 *
 * Several searches do not return images: unifiedItemSearch (the search screen)
 * and recordingSearch return none at all, however the request is phrased.  The
 * artwork exists, it just has to be asked for by collection or content id, one
 * lookup per show.
 *
 * Those lookups are cached by id for the life of the process, so scrolling a
 * list back and forth, or several episodes of one series, cost a single request
 * rather than one per row.  Misses are cached too -- but only real ones, not a
 * failed request, or a TiVo that was briefly asleep would leave a show
 * pictureless until restart.
 */
public class ArtworkLoader {
  /** Collection or content id -> artwork url, or null when it truly has none. */
  private static final Map<String, String> sUrlCache =
      new HashMap<String, String>();

  /**
   * Set the artwork for one row, looking it up if the item does not carry it.
   *
   * The image view is tagged with the id being fetched, so a response that
   * arrives after the view has been recycled onto a different show is dropped
   * instead of stamping the wrong picture on it.
   */
  public static void load(final Context context, JsonNode item,
      final ImageView imageView, final View progressView) {
    load(context, Utils.findImageUrl(item),
        item.has("collectionId") ? item.path("collectionId").asText() : null,
        item.has("contentId") ? item.path("contentId").asText() : null,
        imageView, progressView);
  }

  /**
   * As {@link #load(Context, JsonNode, ImageView, View)}, for callers that
   * already hold the ids rather than a row to read them out of -- the Explore
   * screen knows them from the intent that opened it, which is more reliable
   * than the content it got back.
   */
  public static void load(final Context context, final String directUrl,
      final String collectionId, final String contentId,
      final ImageView imageView, final View progressView) {
    if (directUrl != null) {
      // Claim the view on this path too -- see the setTag below.
      imageView.setTag(R.id.artwork_key, null);
      download(context, imageView, progressView, directUrl);
      return;
    }

    final String key = collectionId != null ? collectionId : contentId;
    if (key == null) {
      // A person row, say; nothing to look up.
      imageView.setTag(R.id.artwork_key, null);
      download(context, imageView, progressView, null);
      return;
    }

    // Claim the view for this id before taking any path, not just the one
    // that makes a request.  A row that returns synchronously below would
    // otherwise keep whatever id the *previous* occupant of this recycled
    // view was fetching, and that older response would still match the tag
    // and stamp its picture on top of this row.
    imageView.setTag(R.id.artwork_key, key);

    if (sUrlCache.containsKey(key)) {
      download(context, imageView, progressView, sUrlCache.get(key));
      return;
    }
    MindRpc.addRequest(new ImageSearch(collectionId, contentId),
        new MindRpcResponseListener() {
          public void onResponse(MindRpcResponse response) {
            JsonNode body = response.getBody();
            String url = null;
            if ("error".equals(body.path("type").asText())) {
              // Do NOT cache this.  An error is not an answer -- a TiVo that
              // was briefly asleep would otherwise be remembered as "this
              // show has no artwork" for the rest of the process, which is
              // exactly what this class promises not to do.  Fall through so
              // the row still loses its spinner, and let the next render of
              // this id ask again.
              Utils.log("ArtworkLoader: error response for " + key);
            } else {
              JsonNode node = body.has("collection")
                  ? body.path("collection").path(0)
                  : body.path("content").path(0);
              url = Utils.findImageUrl(node);
              sUrlCache.put(key, url);
            }

            if (!key.equals(imageView.getTag(R.id.artwork_key))) {
              // Recycled onto another show while we were waiting.
              return;
            }
            download(context, imageView, progressView, url);
          }
        });
  }

  private static void download(Context context, ImageView imageView,
      View progressView, String url) {
    // A null url is fine; the task just clears the spinner and leaves the
    // placeholder in place.
    new DownloadImageTask(context, imageView, progressView).execute(url);
  }
}
