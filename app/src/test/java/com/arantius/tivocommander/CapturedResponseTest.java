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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * What the screens assume about a response, checked against real ones.
 *
 * Everything is read with path(), which answers a missing node instead of
 * failing, so a field that stops arriving shows up as a blank row rather than
 * an error.  These pin the fields each screen depends on.
 */
public class CapturedResponseTest {
  private static void assertHasFields(JsonNode node, String... fields) {
    for (String field : fields) {
      assertTrue("captured row is missing " + field + ": " + node,
          node.has(field));
    }
  }

  @Test
  public void myShowsReadsTheDiskMeterOffBodyConfig() {
    JsonNode config = Fixtures.response("bodyConfigList")
        .path("bodyConfig").path(0);
    assertHasFields(config, "userDiskUsed", "userDiskSize");

    // Strings holding kilobyte counts too large for an int, hence asLong().
    long usedKb = config.path("userDiskUsed").asLong();
    long sizeKb = config.path("userDiskSize").asLong();
    assertTrue(usedKb > 0 && sizeKb > 0);
    assertTrue("used should not exceed capacity", usedKb <= sizeKb);

    int usedMb = (int) Math.floor(usedKb / 1024);
    int sizeMb = (int) Math.floor(sizeKb / 1024);
    assertTrue("megabytes should fit an int", sizeMb > 0);
    int percent = (int) ((double) 100 * usedMb / sizeMb);
    assertTrue("percentage was " + percent, percent >= 0 && percent <= 100);
  }

  @Test
  public void myShowsReadsFoldersAndRecordingsFromOneList() {
    JsonNode items = Fixtures.response("recordingFolderItemList")
        .path("recordingFolderItem");
    assertTrue(items.size() > 0);

    boolean sawFolder = false;
    boolean sawShow = false;
    for (JsonNode item : items) {
      assertHasFields(item, "recordingFolderItemId", "title");
      sawFolder |= item.has("folderItemCount");
      if (item.has("childRecordingId")) {
        sawShow = true;
        assertTrue(item.path("childRecordingId").asText()
            .startsWith("tivo:rc."));
      }
    }
    assertTrue("capture should include a folder", sawFolder);
    assertTrue("capture should include a single show", sawShow);
  }

  @Test
  public void recordingsCarryWhatTheShowRowsDraw() {
    JsonNode recording = Fixtures.response("recordingList")
        .path("recording").path(0);
    assertHasFields(recording, "recordingId", "title", "startTime", "state",
        "channel");
    assertNotNull(Utils.parseDateTimeStr(
        recording.path("startTime").asText()));
  }

  @Test
  public void recordingSearchReturnsNoArtwork() {
    assertTrue(Fixtures.response("recordingList").path("recording").size()
        > 0);
    // Why ArtworkLoader exists: anything reached from My Shows has to look
    // its artwork up separately, by collection or content id.
    for (JsonNode recording : Fixtures.response("recordingList")
        .path("recording")) {
      assertFalse(recording.has("image"));
    }
  }

  @Test
  public void unifiedItemSearchReturnsNoArtworkEither() {
    assertTrue(Fixtures.response("unifiedItemList").path("unifiedItem")
        .size() > 0);
    for (JsonNode item : Fixtures.response("unifiedItemList")
        .path("unifiedItem")) {
      assertFalse(item.has("image"));
    }
  }

  @Test
  public void collectionSearchDoesReturnArtwork() {
    // The other half of that story: asked by id, with no imageRuleset, the
    // service does hand artwork back.
    JsonNode images = Fixtures.response("collectionList")
        .path("collection").path(0).path("image");
    assertTrue(images.size() > 0);
    for (JsonNode image : images) {
      assertHasFields(image, "width", "height", "imageUrl");
      assertTrue(image.path("imageUrl").asText().startsWith("http"));
    }
  }

  @Test
  public void searchResultsCarryAnIdToOpen() {
    JsonNode items = Fixtures.response("unifiedItemList").path("unifiedItem");
    assertTrue(items.size() > 0);
    for (JsonNode item : items) {
      // Rows arrive flat, which is why Search.onItemClick reads these three
      // ids straight off the item.
      boolean openable = item.has("collectionId") || item.has("contentId")
          || item.has("personId");
      assertTrue("result row has nothing to open: " + item, openable);
    }
  }

  @Test
  public void idSequenceIsJustIdsWithBoundaryFlags() {
    JsonNode sequence = Fixtures.response("idSequence");
    assertEquals("idSequence", sequence.path("type").asText());
    assertHasFields(sequence, "objectIdAndType", "isTop", "isBottom");
    assertTrue(sequence.path("objectIdAndType").size() > 0);
    assertTrue(sequence.path("objectIdAndType").path(0).isTextual());
  }

  @Test
  public void nowShowingReadsPlaybackPosition() {
    JsonNode event = Fixtures.response("videoPlaybackInfoEvent");
    assertHasFields(event, "position", "speed", "begin", "end");
    // The millisecond counters arrive as strings; only speed is a number.
    // asInt()/asLong() parse a numeric string, which is why NowShowing gets
    // away with reading them directly.
    assertTrue(event.path("position").isTextual());
    assertTrue(event.path("position").asLong() >= 0);
    assertTrue(event.path("virtualPosition").asLong() > 0);
    assertTrue(event.path("speed").isNumber());
  }

  @Test
  public void noCapturedFixtureLeaksTheDeviceIdentity() {
    for (String type : new String[] {"bodyConfigList", "recordingList",
        "subscriptionList", "unifiedItemList"}) {
      String json = Utils.stringifyToJson(Fixtures.response(type));
      assertFalse(type + " should carry no real TSN",
          json.matches("(?s).*tsn:(?!0+\").*"));
    }
    assertEquals(Fixtures.MAK,
        Fixtures.requests("bodyAuthenticate").get(0).path("credential")
            .path("key").asText());
  }

  @Test
  public void creditRowsCanOpenAPersonScreen() {
    // The Credits page asks for these five; the Explore page's own search
    // asks for a narrower credit and gets first/last/role only, so take the
    // variant that carries the wider one.
    JsonNode credits = null;
    for (JsonNode response : Fixtures.responses("collectionList")) {
      JsonNode candidate = response.path("collection").path(0).path("credit");
      if (candidate.path(0).has("personId")
          && candidate.path(0).has("first")) {
        credits = candidate;
      }
    }
    assertNotNull("no captured credits carry a personId", credits);
    assertTrue(credits.size() > 0);
    for (JsonNode credit : credits) {
      assertHasFields(credit, "first", "last", "role", "personId");
      // Utils.ucFirst() is handed this straight, and would have thrown on "".
      assertFalse("role should not be blank",
          credit.path("role").asText().isEmpty());
    }
  }

  @Test
  public void seasonPassReadsItsSubscriptions() {
    JsonNode subscriptions =
        Fixtures.response("subscriptionList").path("subscription");
    assertTrue(subscriptions.size() > 0);
    for (JsonNode subscription : subscriptions) {
      assertHasFields(subscription, "subscriptionId", "title", "idSetSource",
          "showStatus", "keepBehavior", "maxRecordings", "objectIdAndType");
      // The Season Pass manager reorders by moving these ids around.
      assertTrue(subscription.path("subscriptionId").asText()
          .startsWith("tivo:sb."));
      assertTrue("a season pass is tied to a collection",
          subscription.path("idSetSource").has("collectionId"));
    }
  }

  @Test
  public void showRowsReadTheChannelOffTheRecording() {
    JsonNode channel = Fixtures.response("recordingList")
        .path("recording").path(0).path("channel");
    assertHasFields(channel, "channelNumber", "callSign", "logoIndex");
    assertFalse(channel.path("callSign").asText().isEmpty());
  }

  @Test
  public void recordingStatesAreOnesTheAppHandles() {
    // Every branch in Explore and MyShows keys off this string; an unknown
    // one silently falls through to no buttons and no status icon.
    Set<String> handled = new HashSet<String>(Arrays.asList("inProgress",
        "complete", "scheduled", "deleted", "cancelled", "expired"));
    int seen = 0;
    for (JsonNode response : Fixtures.responses("recordingList")) {
      for (JsonNode recording : response.path("recording")) {
        String state = recording.path("state").asText();
        assertTrue("unhandled recording state: " + state,
            handled.contains(state));
        seen++;
      }
    }
    assertTrue("fixtures should carry recordings", seen > 0);
  }

  @Test
  public void nowShowingCanReadAnIdleBox() {
    JsonNode whatsOn = Fixtures.response("whatsOnList").path("whatsOn").path(0);
    assertHasFields(whatsOn, "playbackType");
    // With nothing playing there is no recordingId or offerId to follow,
    // which is why NowShowing has to cope with both being absent.
    assertEquals("idle", whatsOn.path("playbackType").asText());
    assertFalse(whatsOn.has("recordingId"));
    assertFalse(whatsOn.has("offerId"));
  }

  @Test
  public void everyCapturedResponseIsFiledUnderItsOwnType() {
    assertTrue(Fixtures.capturedTypes("response").size() > 5);
    for (String type : Fixtures.capturedTypes("response")) {
      for (JsonNode response : Fixtures.responses(type)) {
        assertEquals(type, response.path("type").asText());
      }
    }
  }
}
