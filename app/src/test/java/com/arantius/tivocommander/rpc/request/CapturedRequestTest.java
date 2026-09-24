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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;

import org.junit.Before;
import org.junit.Test;

import com.arantius.tivocommander.Device;
import com.arantius.tivocommander.Fixtures;
import com.arantius.tivocommander.Utils;
import com.arantius.tivocommander.rpc.MindRpc;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Golden tests: each request still goes out the way a working app sent it.
 *
 * The expected bodies were captured off a live session in which every one of
 * them was answered, so a failure means a request has drifted from one the
 * service is known to accept -- a better place to find out than watching a
 * screen come up empty.  Several classes share one RPC type, so a request has
 * to match any one of the captured bodies of its type.  When a request changes
 * on purpose, re-capture rather than hand-edit the fixture.
 */
public class CapturedRequestTest {
  @Before
  public void setUpDevice() {
    Device device = new Device();
    device.tsn = Fixtures.TSN;
    MindRpc.mTivoDevice = device;
  }

  private static void assertWasCaptured(MindRpcRequest request) {
    String type = request.getReqType();
    JsonNode actual = Utils.parseJson(Fixtures.canonicalizeIds(
        Utils.stringifyToJson(request.getDataMap())));
    List<JsonNode> captured = Fixtures.requests(type);
    for (JsonNode expected : captured) {
      if (expected.equals(actual)) {
        return;
      }
    }
    fail(request.getClass().getSimpleName() + " matches none of the "
        + captured.size() + " captured " + type + " bodies.\nBuilt:\n"
        + Utils.stringifyToPrettyJson(actual) + "\nCaptured:\n"
        + Utils.stringifyToPrettyJson(captured));
  }

  @Test
  public void bodyAuthenticateMatchesCapture() {
    assertWasCaptured(new BodyAuthenticate(Fixtures.MAK));
  }

  @Test
  public void bodyConfigSearchMatchesCapture() {
    assertWasCaptured(new BodyConfigSearch());
  }

  @Test
  public void whatsOnSearchMatchesCapture() {
    assertWasCaptured(new WhatsOnSearch());
  }

  @Test
  public void videoPlaybackInfoEventRegisterMatchesCapture() {
    assertWasCaptured(new VideoPlaybackInfoEventRegister(1001));
  }

  @Test
  public void cancelMatchesCapture() {
    assertWasCaptured(new CancelRpc(7));
  }

  @Test
  public void imageSearchMatchesCapture() {
    assertWasCaptured(new ImageSearch("tivo:cl.343523398", null));
  }

  @Test
  public void collectionSearchMatchesCapture() {
    assertWasCaptured(new CollectionSearch("tivo:cl.343523398"));
  }

  @Test
  public void creditsSearchMatchesCapture() {
    assertWasCaptured(new CreditsSearch("tivo:cl.343523398", null));
  }

  @Test
  public void recordingSearchMatchesCapture() {
    assertWasCaptured(new RecordingSearch("tivo:rc.16716469"));
  }

  @Test
  public void subscriptionSearchMatchesCapture() {
    assertWasCaptured(new SubscriptionSearch("tivo:cl.343523398"));
  }

  @Test
  public void unifiedItemSearchMatchesCapture() {
    assertWasCaptured(new UnifiedItemSearch("trek*"));
  }

  @Test
  public void recordingFolderItemSearchMatchesCapture() {
    assertWasCaptured(new RecordingFolderItemSearch((String) null,
        "startTime"));
  }

  @Test
  public void nothingTheLiveAppSentCarriedAnImageRuleset() {
    assertTrue(Fixtures.capturedTypes("request").size() > 5);
    // MindRpcRequestTest asserts this of the code; this asserts it of the
    // traffic a working session actually produced, which is the evidence that
    // the code being right is what makes artwork arrive.
    for (String type : Fixtures.capturedTypes("request")) {
      for (JsonNode body : Fixtures.requests(type)) {
        assertFalse(type + " capture carries an imageRuleset",
            body.toString().contains("imageRuleset"));
      }
    }
  }

  @Test
  public void everyCapturedRequestIsFiledUnderItsOwnType() {
    assertTrue(Fixtures.capturedTypes("request").size() > 5);
    for (String type : Fixtures.capturedTypes("request")) {
      for (JsonNode body : Fixtures.requests(type)) {
        assertEquals(type, body.path("type").asText());
      }
    }
  }
}
