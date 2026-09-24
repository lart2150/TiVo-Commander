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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.net.Uri;
import android.widget.TextView;

import com.google.android.material.tabs.TabLayout;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowLooper;

import com.arantius.tivocommander.rpc.FakeTivo;
import com.fasterxml.jackson.databind.JsonNode;

/** The Explore tabs, and the pages inside them. */
@RunWith(RobolectricTestRunner.class)
public class ExploreTest {
  private ActivityController<ExploreTabs> mController;
  private FakeTivo mTivo;

  @After
  public void tearDown() {
    if (mController != null) {
      mController.close();
    }
    FakeTivo.uninstall();
  }

  private ExploreTabs startWith(Intent intent) {
    mTivo = FakeTivo.install()
        .answer("collectionSearch", Fixtures.response("collectionList"))
        .answer("subscriptionSearch", Fixtures.response("subscriptionList"));
    mController = Robolectric.buildActivity(ExploreTabs.class, intent).setup();
    ShadowLooper.runUiThreadTasksIncludingDelayedTasks();
    return mController.get();
  }

  private static Intent byCollection(String collectionId) {
    Intent intent =
        new Intent(RuntimeEnvironment.getApplication(), ExploreTabs.class);
    intent.putExtra("collectionId", collectionId);
    return intent;
  }

  @Test
  public void aCollectionGetsAllThreeTabs() {
    ExploreTabs activity = startWith(byCollection("tivo:cl.22439"));
    TabLayout tabs = activity.findViewById(R.id.explore_tab_layout);
    assertNotNull(tabs);
    assertEquals(3, tabs.getTabCount());
  }

  @Test
  public void thePageAsksForTheCollectionItWasGiven() {
    startWith(byCollection("tivo:cl.22439"));
    JsonNode body = Utils.parseJson(Utils.stringifyToJson(
        mTivo.firstOfType("collectionSearch").getDataMap()));
    assertEquals("tivo:cl.22439", body.path("collectionId").path(0).asText());
  }

  @Test
  public void theShowDetailsAreDrawnFromTheResponse() {
    ExploreTabs activity = startWith(byCollection("tivo:cl.22439"));
    mTivo.deliver();
    ShadowLooper.runUiThreadTasksIncludingDelayedTasks();

    JsonNode collection =
        Fixtures.response("collectionList").path("collection").path(0);
    TextView title = activity.findViewById(R.id.content_title);
    assertNotNull("the page should have swapped in its layout", title);
    assertEquals(collection.path("title").asText(),
        title.getText().toString());

    TextView details = activity.findViewById(R.id.content_details);
    assertTrue("the description should be on screen",
        details.getText().toString()
            .contains(collection.path("description").asText()));
  }

  @Test
  public void theCreditsAreListedUnderTheDescription() {
    ExploreTabs activity = startWith(byCollection("tivo:cl.22439"));
    mTivo.deliver();
    ShadowLooper.runUiThreadTasksIncludingDelayedTasks();

    JsonNode credits = Fixtures.response("collectionList").path("collection")
        .path(0).path("credit");
    TextView view = activity.findViewById(R.id.content_credits);
    assertNotNull(view);
    String shown = view.getText().toString();
    for (JsonNode credit : credits) {
      String role = credit.path("role").asText();
      if ("actor".equals(role) || "host".equals(role)
          || "guestStar".equals(role)) {
        assertTrue("missing " + credit.path("last").asText() + " from: "
            + shown, shown.contains(credit.path("last").asText()));
      }
    }
  }

  @Test
  public void aDeepLinkIsReadTheSameAsAnIntentExtra() {
    Intent intent = new Intent(Intent.ACTION_VIEW,
        Uri.parse("https://www3.tivo.com/tivo-tco/program/show.do"
            + "?collectionId=tivo:cl.22439"));
    intent.setClass(RuntimeEnvironment.getApplication(), ExploreTabs.class);
    startWith(intent);

    JsonNode body = Utils.parseJson(Utils.stringifyToJson(
        mTivo.firstOfType("collectionSearch").getDataMap()));
    assertEquals("tivo:cl.22439", body.path("collectionId").path(0).asText());
  }

  @Test
  public void nothingToShowIsNotAskedFor() {
    // With no ids at all the page has nothing to search for, and says so
    // rather than sending a request that cannot be answered.
    mTivo = FakeTivo.install();
    Intent intent =
        new Intent(RuntimeEnvironment.getApplication(), ExploreTabs.class);
    mController = Robolectric.buildActivity(ExploreTabs.class, intent).setup();
    ShadowLooper.runUiThreadTasksIncludingDelayedTasks();

    assertTrue("should not have searched: " + mTivo.sentTypes(),
        mTivo.sentTypes().isEmpty());
  }
}
