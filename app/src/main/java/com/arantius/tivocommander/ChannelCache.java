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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import android.content.Context;
import android.content.SharedPreferences;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One TiVo's channel lineup, kept so the guide need not discover it again.
 *
 * Working out the lineup costs a request per {@link
 * com.arantius.tivocommander.rpc.request.GridRowSearch#PAGE_SIZE} channels and
 * has to finish before the grid can say how long it is -- and it was being
 * paid again every time the guide was opened, which is every time you come
 * back from a programme.  A lineup changes when the service does, which is
 * rarely, so it is worth keeping.
 *
 * Kept per TiVo, keyed by body id: two boxes on one account can be on
 * different services, in different houses, with entirely different channels.
 *
 * Nothing here is trusted to still be true.  The guide checks each page of
 * listings it gets back against what was cached for those rows, and throws the
 * whole lineup away the moment they disagree -- that check is what makes it
 * safe to hand out a lineup saved days ago, and it costs no request of its own
 * because the listings had to be fetched anyway.
 */
public final class ChannelCache {
  /**
   * How long a saved lineup is offered for.
   *
   * Only a backstop: a lineup that changed is caught by the guide's own check
   * on the next page of listings, whatever its age.  This bounds how long a
   * box that is never opened again keeps its channels on disk.
   */
  private static final long MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L;

  private static final String PREFS_NAME = "guide_channels";
  private static final String KEY_SAVED_AT = "savedAt";
  private static final String KEY_CHANNEL = "channel";

  /**
   * The lineups this process has, by body id.
   *
   * The disk copy is what survives the app being killed; this is what makes
   * re-opening the guide within a session free, and it is also the only copy
   * when the box has no body id to key a file by.
   */
  private static final Map<String, List<JsonNode>> sMemory =
      new HashMap<String, List<JsonNode>>();

  private ChannelCache() {
  }

  /**
   * The lineup saved for a box, or null when there is none worth offering.
   *
   * The returned list is a copy: the guide builds rows from it and must not be
   * able to edit what is cached by doing so.
   */
  public static synchronized List<JsonNode> get(Context context, String tsn) {
    if (!usable(tsn)) {
      return null;
    }
    List<JsonNode> channels = sMemory.get(tsn);
    if (channels == null) {
      channels = read(context, tsn);
      if (channels != null) {
        sMemory.put(tsn, channels);
      }
    }
    if (channels == null || channels.isEmpty()) {
      return null;
    }
    return new ArrayList<JsonNode>(channels);
  }

  /** Keep a lineup for a box, in memory and on disk. */
  public static synchronized void put(
      Context context, String tsn, List<JsonNode> channels) {
    if (!usable(tsn) || channels == null || channels.isEmpty()) {
      return;
    }
    sMemory.put(tsn, new ArrayList<JsonNode>(channels));
    write(context, tsn, channels);
  }

  /** Forget a box's lineup: it no longer matches what the box answers with. */
  public static synchronized void clear(Context context, String tsn) {
    if (!usable(tsn)) {
      return;
    }
    sMemory.remove(tsn);
    prefs(context).edit().remove(tsn).apply();
  }

  /** Drop everything, so one test cannot seed the next. */
  public static synchronized void clearAll(Context context) {
    sMemory.clear();
    prefs(context).edit().clear().apply();
  }

  /**
   * A body id the cache can be keyed by.
   *
   * {@link Device#tsn} starts out "-" and stays there until the box answers
   * with its own id, so anything shorter than a real one would have every
   * hand-added box sharing a single entry -- and handing one box's channels to
   * another is exactly the mistake this is keyed to avoid.
   */
  private static boolean usable(String tsn) {
    return tsn != null && tsn.length() > 4;
  }

  private static SharedPreferences prefs(Context context) {
    return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
  }

  private static List<JsonNode> read(Context context, String tsn) {
    String saved = prefs(context).getString(tsn, null);
    if (saved == null) {
      return null;
    }
    JsonNode node = Utils.parseJson(saved);
    if (node == null) {
      // Unreadable, so it will never be readable: take it out of the way.
      prefs(context).edit().remove(tsn).apply();
      return null;
    }
    long age = System.currentTimeMillis() - node.path(KEY_SAVED_AT).asLong();
    if (age < 0 || age > MAX_AGE_MS) {
      prefs(context).edit().remove(tsn).apply();
      return null;
    }
    List<JsonNode> channels = new ArrayList<JsonNode>();
    JsonNode array = node.path(KEY_CHANNEL);
    for (int i = 0; i < array.size(); i++) {
      channels.add(array.path(i));
    }
    return Collections.unmodifiableList(channels);
  }

  private static void write(Context context, String tsn,
      List<JsonNode> channels) {
    Map<String, Object> saved = new HashMap<String, Object>();
    saved.put(KEY_SAVED_AT, System.currentTimeMillis());
    saved.put(KEY_CHANNEL, channels);
    String json = Utils.stringifyToJson(saved);
    if (json == null) {
      return;
    }
    // apply() rather than commit(): nothing downstream waits on this, and a
    // lineup is tens of kilobytes to serialise.
    prefs(context).edit().putString(tsn, json).apply();
  }
}
