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

package com.arantius.tivocommander;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;

public class UtilsTest {
  private static long utcMillis(int year, int month, int day, int hour,
      int minute, int second) {
    Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    cal.clear();
    cal.set(year, month - 1, day, hour, minute, second);
    return cal.getTimeInMillis();
  }

  @Test
  public void parseDateStr_readsTheDateAsUtc() {
    Date parsed = Utils.parseDateStr("2026-09-18");
    assertNotNull(parsed);
    assertEquals(utcMillis(2026, 9, 18, 0, 0, 0), parsed.getTime());
  }

  @Test
  public void parseDateTimeStr_readsTheTimeAsUtc() {
    Date parsed = Utils.parseDateTimeStr("2026-09-18 02:05:18");
    assertNotNull(parsed);
    assertEquals(utcMillis(2026, 9, 18, 2, 5, 18), parsed.getTime());
  }

  @Test
  public void parseDateTimeStr_returnsNullRatherThanThrowing() {
    assertNull(Utils.parseDateTimeStr("not a date"));
    assertNull(Utils.parseDateTimeStr(""));
    assertNull(Utils.parseDateStr("18/09/2026"));
  }

  @Test
  public void parseDateTimeStr_ignoresTrailingText() {
    assertEquals(utcMillis(2026, 9, 18, 2, 5, 18),
        Utils.parseDateTimeStr("2026-09-18 02:05:18 extra").getTime());
  }

  @Test
  public void parseDateTimeStr_readsRealRecordingTimes() {
    JsonNode recordings = Fixtures.response("recordingList").path("recording");
    assertTrue(recordings.size() > 0);
    int checked = 0;
    for (JsonNode recording : recordings) {
      for (String field : new String[] {"startTime", "endTime",
          "scheduledStartTime", "scheduledEndTime", "actualStartTime",
          "actualEndTime"}) {
        if (!recording.has(field)) {
          continue;
        }
        String value = recording.path(field).asText();
        assertNotNull(field + " should parse: " + value,
            Utils.parseDateTimeStr(value));
        checked++;
      }
    }
    assertTrue("fixture should carry timestamps", checked > 0);
  }

  @Test
  public void join_gluesAndSkipsEmptyParts() {
    assertEquals("a, b, c", Utils.join(", ", "a", "b", "c"));
    assertEquals("a", Utils.join(", ", "a"));
    assertEquals("", Utils.join(", "));
  }

  @Test
  public void join_dropsNullPartsWithoutLeavingASeparator() {
    List<String> parts = new ArrayList<String>(
        Arrays.asList("first", null, "last"));
    assertEquals("first, last", Utils.join(", ", parts));
  }

  @Test
  public void ucFirst_capitalisesWithoutTouchingTheRest() {
    assertEquals("Actor", Utils.ucFirst("actor"));
    assertEquals("GuestStar", Utils.ucFirst("guestStar"));
    assertEquals("Host", Utils.ucFirst("Host"));
  }

  @Test
  public void ucFirst_handlesNullAndEmpty() {
    // Both reachable from a credit row: asText() answers "" for a field the
    // service left out, and Person.findRole() can return null.
    assertNull(Utils.ucFirst(null));
    assertEquals("", Utils.ucFirst(""));
  }

  @Test
  public void findImageUrl_picksTheBiggestImage() {
    JsonNode node = Utils.parseJson("{\"image\": ["
        + "{\"width\": 70, \"height\": 53, \"imageUrl\": \"small\"},"
        + "{\"width\": 360, \"height\": 270, \"imageUrl\": \"big\"},"
        + "{\"width\": 139, \"height\": 104, \"imageUrl\": \"middle\"}]}");
    assertEquals("big", Utils.findImageUrl(node));
  }

  @Test
  public void findImageUrl_prefersTivoHostOverTheProvidersCdn() {
    // The provider copy is bigger, and first, and still must not win: those
    // hosts are routinely unreachable from the customer's own network.
    JsonNode node = Utils.parseJson("{\"image\": ["
        + "{\"width\": 360, \"height\": 270,"
        + " \"imageUrl\": \"http://71.7.197.216:8080/big.jpg\"},"
        + "{\"width\": 139, \"height\": 104,"
        + " \"imageUrl\": \"http://i.tivo.com/small.jpg\"}]}");
    assertEquals("http://i.tivo.com/small.jpg", Utils.findImageUrl(node));
  }

  @Test
  public void findImageUrl_picksTheBiggestTivoHostedImage() {
    // Every image reported as 1x1 is what the live service does now, so the
    // preference has to hold when size cannot break the tie.
    JsonNode node = Utils.parseJson("{\"image\": ["
        + "{\"width\": 1, \"height\": 1,"
        + " \"imageUrl\": \"https://atlanticbb.net:8080/a.jpg\"},"
        + "{\"width\": 1, \"height\": 1,"
        + " \"imageUrl\": \"https://i.tivo.com/b.jpg\"},"
        + "{\"width\": 1, \"height\": 1,"
        + " \"imageUrl\": \"https://i.tivo.com/c.jpg\"}]}");
    assertEquals("https://i.tivo.com/b.jpg", Utils.findImageUrl(node));
  }

  @Test
  public void findImageUrl_fallsBackToAProviderUrlWhenThatIsAllThereIs() {
    JsonNode node = Utils.parseJson("{\"image\": ["
        + "{\"width\": 70, \"height\": 53,"
        + " \"imageUrl\": \"http://24.138.202.201:4567/small.jpg\"},"
        + "{\"width\": 360, \"height\": 270,"
        + " \"imageUrl\": \"http://24.138.202.201:4567/big.jpg\"}]}");
    assertEquals("http://24.138.202.201:4567/big.jpg",
        Utils.findImageUrl(node));
  }

  @Test
  public void findImageUrl_isNotFooledByTivoInTheWrongPartOfAUrl() {
    JsonNode node = Utils.parseJson("{\"image\": ["
        + "{\"width\": 10, \"height\": 10,"
        + " \"imageUrl\": \"http://evil.example.com/i.tivo.com/x.jpg\"},"
        + "{\"width\": 1, \"height\": 1,"
        + " \"imageUrl\": \"http://i.tivo.com/real.jpg\"}]}");
    assertEquals("http://i.tivo.com/real.jpg", Utils.findImageUrl(node));
  }

  @Test
  public void findImageUrl_returnsNullWhenThereIsNoArtwork() {
    assertNull(Utils.findImageUrl(Utils.parseJson("{}")));
    assertNull(Utils.findImageUrl(Utils.parseJson("{\"image\": []}")));
  }

  @Test
  public void findImageUrl_ignoresImagesWithNoDimensions() {
    JsonNode node = Utils.parseJson("{\"image\": ["
        + "{\"imageUrl\": \"sizeless\"},"
        + "{\"width\": 100, \"height\": 75, \"imageUrl\": \"sized\"}]}");
    assertEquals("sized", Utils.findImageUrl(node));
  }

  @Test
  public void findImageUrl_picksOneOfTheRealImagesOffered() {
    // The current service reports every image as 1x1, so "largest" cannot
    // choose between them -- but it still has to return one of them.
    JsonNode collection = Fixtures.response("collectionList")
        .path("collection").path(0);
    String url = Utils.findImageUrl(collection);
    assertNotNull("captured collection should carry artwork", url);
    boolean offered = false;
    for (JsonNode image : collection.path("image")) {
      offered |= url.equals(image.path("imageUrl").asText());
    }
    assertTrue("returned a url that was not in the list: " + url, offered);
  }

  @Test
  public void parseJson_returnsNullOnGarbage() {
    assertNull(Utils.parseJson("{not json"));
  }

  @Test
  public void stringifyToJson_roundTripsThroughParseJson() {
    JsonNode original = Fixtures.response("bodyConfigList");
    assertEquals(original, Utils.parseJson(Utils.stringifyToJson(original)));
  }

  @Test
  public void logBuffer_staysBounded() {
    for (int i = 0; i < Utils.LOG_BUFFER_SIZE + 50; i++) {
      Utils.logAddToBuffer("line " + i, "I");
    }
    String buffer = Utils.logBufferAsString();
    int lines = buffer.isEmpty() ? 0 : buffer.split("\n").length;
    assertTrue("buffer grew to " + lines + " lines",
        lines < Utils.LOG_BUFFER_SIZE + 1);
    assertTrue("oldest lines should have been dropped",
        buffer.contains("line " + (Utils.LOG_BUFFER_SIZE + 49)));
  }
}
