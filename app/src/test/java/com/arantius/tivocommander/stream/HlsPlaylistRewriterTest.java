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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

/**
 * Pinned against playlists real TiVos actually served.
 *
 * complete_* came off a Bolt on 2026-09-21 for a finished recording; growing_*
 * and abr_master are kmttg's captures of a session still being transcoded.  The
 * two disagree about #EXT-X-ENDLIST, which is the whole reason this class
 * exists -- and the reason it has to leave the complete one alone.
 */
public class HlsPlaylistRewriterTest {
  private static String fixture(String name) {
    try (InputStream in = HlsPlaylistRewriterTest.class
        .getResourceAsStream("/hls/" + name)) {
      if (in == null) {
        throw new IllegalStateException("missing fixture " + name);
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buf = new byte[8192];
      int n;
      while ((n = in.read(buf)) > 0) {
        out.write(buf, 0, n);
      }
      return out.toString(StandardCharsets.UTF_8.name());
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  public void marksAGrowingPlaylistAsEvent() {
    String raw = fixture("growing_variant.m3u8");
    assertFalse(raw.contains("#EXT-X-ENDLIST"));

    String out = HlsPlaylistRewriter.rewrite(raw);
    assertTrue(out.contains("#EXT-X-PLAYLIST-TYPE:EVENT"));
    // Directly after #EXTM3U, which must stay the first line.  Line endings
    // are whatever the source used -- this fixture is CRLF, from a Windows
    // checkout; the box itself serves LF.  See preservesCarriageReturns.
    String[] lines = out.split("\r?\n");
    assertEquals("#EXTM3U", lines[0]);
    assertEquals("#EXT-X-PLAYLIST-TYPE:EVENT", lines[1]);
  }

  @Test
  public void keepsEverySegmentOfAGrowingPlaylist() {
    String raw = fixture("growing_variant.m3u8");
    String out = HlsPlaylistRewriter.rewrite(raw);
    assertEquals(countSegments(raw), countSegments(out));
    assertTrue(out.contains("#EXT-X-KEY:METHOD=AES-128"));
    assertTrue(out.contains("#EXT-X-TARGETDURATION:2"));
  }

  @Test
  public void leavesACompletedPlaylistUntouched() {
    // The common case: a finished recording is served whole, with an end tag,
    // and is already a VOD playlist that starts at segment zero on its own.
    String raw = fixture("complete_variant.m3u8");
    assertTrue(raw.contains("#EXT-X-ENDLIST"));
    assertSame(raw, HlsPlaylistRewriter.rewrite(raw));
  }

  @Test
  public void leavesMasterPlaylistsUntouched() {
    // A playlist type on a master is invalid, so neither the single-variant
    // master a Bolt served nor the seven-variant ABR ladder may be rewritten.
    for (String name : new String[] { "complete_master.m3u8", "abr_master.m3u8" }) {
      String raw = fixture(name);
      assertSame(name, raw, HlsPlaylistRewriter.rewrite(raw));
    }
  }

  @Test
  public void leavesAPlaylistThatAlreadyStatesItsType() {
    String raw = "#EXTM3U\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXTINF:2,\n0\n";
    assertSame(raw, HlsPlaylistRewriter.rewrite(raw));
  }

  @Test
  public void preservesCarriageReturns() {
    String raw = "#EXTM3U\r\n#EXT-X-TARGETDURATION:2\r\n#EXTINF:2,\r\n0\r\n";
    String out = HlsPlaylistRewriter.rewrite(raw);
    assertTrue(out.startsWith("#EXTM3U\r\n#EXT-X-PLAYLIST-TYPE:EVENT\r\n"));
  }

  @Test
  public void survivesNonsense() {
    assertEquals(null, HlsPlaylistRewriter.rewrite(null));
    assertEquals("", HlsPlaylistRewriter.rewrite(""));
    assertEquals("#EXTM3U", HlsPlaylistRewriter.rewrite("#EXTM3U"));
  }

  private static int countSegments(String playlist) {
    int n = 0;
    for (String line : playlist.split("\r?\n")) {
      if (!line.trim().isEmpty() && !line.startsWith("#")) {
        n++;
      }
    }
    return n;
  }
}
