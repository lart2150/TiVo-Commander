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

import android.content.Intent;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;

import com.arantius.tivocommander.rpc.FakeTivo;
import com.arantius.tivocommander.rpc.request.MindRpcRequest;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Scheduling a recording -- the paths that cannot be exercised against a real
 * box, because pressing the button there actually schedules something on
 * someone's TiVo.  Nothing here reaches the network.
 */
@RunWith(RobolectricTestRunner.class)
public class SubscribeTest {
  private ActivityController<?> mController;
  private FakeTivo mTivo;

  @After
  public void tearDown() {
    if (mController != null) {
      mController.close();
    }
    FakeTivo.uninstall();
  }

  private static Intent intentFor(Class<?> screen, String... extras) {
    Intent intent = new Intent(RuntimeEnvironment.getApplication(), screen);
    for (int i = 0; i < extras.length; i += 2) {
      intent.putExtra(extras[i], extras[i + 1]);
    }
    return intent;
  }

  private static JsonNode bodyOf(MindRpcRequest request) {
    assertNotNull("no such request was made", request);
    return Utils.parseJson(Utils.stringifyToJson(request.getDataMap()));
  }

  private SubscribeOffer startOffer() {
    mTivo = FakeTivo.install();
    ActivityController<SubscribeOffer> controller =
        Robolectric.buildActivity(SubscribeOffer.class,
            intentFor(SubscribeOffer.class, "offerId", "tivo:of.123",
                "contentId", "tivo:ct.456"));
    mController = controller.setup();
    return controller.get();
  }

  @Test
  public void recordingOneEpisodeAsksForThatOfferAlone() {
    SubscribeOffer activity = startOffer();
    activity.doSubscribe(activity.findViewById(R.id.until));

    JsonNode body = bodyOf(mTivo.firstOfType("subscribe"));
    assertEquals(Fixtures.TSN, body.path("bodyId").asText());
    assertEquals("singleOfferSource",
        body.path("idSetSource").path("type").asText());
    assertEquals("tivo:of.123",
        body.path("idSetSource").path("offerId").asText());
    assertEquals("tivo:ct.456",
        body.path("idSetSource").path("contentId").asText());
    assertFalse("one episode is not a season pass",
        body.has("maxRecordings"));
  }

  @Test
  public void theKeepSettingGoesOutWithIt() {
    SubscribeOffer activity = startOffer();
    activity.doSubscribe(activity.findViewById(R.id.until));

    JsonNode body = bodyOf(mTivo.firstOfType("subscribe"));
    // The first entry of the "until" spinner, which is what is selected.
    assertEquals("fifo", body.path("keepBehavior").asText());
    assertEquals("best", body.path("recordingQuality").asText());
  }

  @Test
  public void onTimeStartAndStopSendNoPaddingAtAll() {
    // Padding of zero is left out rather than sent as 0, so that the TiVo
    // keeps its own default.
    SubscribeOffer activity = startOffer();
    activity.doSubscribe(activity.findViewById(R.id.until));

    JsonNode body = bodyOf(mTivo.firstOfType("subscribe"));
    assertFalse(body.has("startTimePadding"));
    assertFalse(body.has("endTimePadding"));
  }

  @Test
  public void subscribingWaitsForTheAnswerBeforeLeaving() {
    SubscribeOffer activity = startOffer();
    activity.doSubscribe(activity.findViewById(R.id.until));

    assertFalse("should still be up while the request is in flight",
        activity.isFinishing());
    mTivo.deliver();
  }

  @Test
  public void aSeasonPassScreenLooksUpItsChannelsAndAnyExistingPass() {
    mTivo = FakeTivo.install();
    ActivityController<SubscribeCollection> controller =
        Robolectric.buildActivity(SubscribeCollection.class,
            intentFor(SubscribeCollection.class, "collectionId",
                "tivo:cl.22439"));
    mController = controller.setup();

    assertTrue("should look up the channels: " + mTivo.sentTypes(),
        mTivo.sentTypes().contains("offerSearch"));
    assertTrue("should look for an existing season pass: "
        + mTivo.sentTypes(), mTivo.sentTypes().contains("subscriptionSearch"));
    assertEquals("tivo:cl.22439",
        bodyOf(mTivo.firstOfType("subscriptionSearch")).path("collectionId")
            .asText());
  }
}
