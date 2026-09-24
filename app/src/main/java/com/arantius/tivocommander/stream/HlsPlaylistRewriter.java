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

package com.arantius.tivocommander.stream;

/**
 * Declares what a TiVo media playlist is, where the box does not.  A finished
 * recording arrives whole with #EXT-X-ENDLIST and is left alone; one still
 * transcoding grows without an end tag, which RFC 8216 reads as a sliding live
 * window, so a player may start at the live edge.  It is really EVENT.
 */
public final class HlsPlaylistRewriter {
  private HlsPlaylistRewriter() {
  }

  private static final String EVENT_TAG = "#EXT-X-PLAYLIST-TYPE:EVENT";

  public static String rewrite(String playlist) {
    if (playlist == null || !isMediaPlaylist(playlist)) {
      return playlist;
    }
    if (contains(playlist, "#EXT-X-PLAYLIST-TYPE")
        || contains(playlist, "#EXT-X-ENDLIST")) {
      return playlist;
    }

    final int firstBreak = playlist.indexOf('\n');
    if (firstBreak < 0) {
      return playlist;
    }
    final String eol =
        firstBreak > 0 && playlist.charAt(firstBreak - 1) == '\r'
            ? "\r\n" : "\n";
    return playlist.substring(0, firstBreak + 1)
        + EVENT_TAG + eol
        + playlist.substring(firstBreak + 1);
  }

  /** #EXTINF appears only in a media playlist, so a master lacks it. */
  private static boolean isMediaPlaylist(String playlist) {
    return contains(playlist, "#EXTM3U") && contains(playlist, "#EXTINF");
  }

  private static boolean contains(String playlist, String tag) {
    return playlist.indexOf(tag) >= 0;
  }
}
