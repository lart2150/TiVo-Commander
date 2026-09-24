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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.widget.ListView;
import androidx.recyclerview.widget.RecyclerView;
import android.widget.TextView;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;

import com.arantius.tivocommander.rpc.FakeTivo;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * The remaining screens, each driven against recorded responses.
 *
 * These are the screens that were impossible to test before: every one of them
 * asks MindRpc for its data in onCreate and gives up if there is no
 * connection, so without a stand-in TiVo they only ever reached the Connect
 * screen.
 */
@RunWith(RobolectricTestRunner.class)
public class ScreenTest {
  private ActivityController<?> mController;
  private FakeTivo mTivo;

  @After
  public void tearDown() {
    if (mController != null) {
      mController.close();
    }
    FakeTivo.uninstall();
  }

  private <T extends android.app.Activity> T start(Class<T> screen,
      Intent intent) {
    ActivityController<T> controller = intent == null
        ? Robolectric.buildActivity(screen)
        : Robolectric.buildActivity(screen, intent);
    mController = controller.setup();
    return controller.get();
  }

  private static Intent intentFor(Class<?> screen, String... extras) {
    Intent intent =
        new Intent(RuntimeEnvironment.getApplication(), screen);
    for (int i = 0; i < extras.length; i += 2) {
      intent.putExtra(extras[i], extras[i + 1]);
    }
    return intent;
  }

  // --- Season Pass -------------------------------------------------------

  @Test
  public void seasonPassListsTheSubscriptionsItIsGiven() {
    mTivo = FakeTivo.install()
        .answer("subscriptionSearch", Fixtures.response("idSequence"))
        .answer("subscriptionSearch", Fixtures.response("subscriptionList"));
    SeasonPass activity = start(SeasonPass.class, null);
    mTivo.deliver();

    assertTrue("should have asked for subscriptions",
        mTivo.sentTypes().contains("subscriptionSearch"));
    int ids = Fixtures.response("idSequence").path("objectIdAndType").size();
    RecyclerView list = activity.findViewById(R.id.season_pass_list);
    assertNotNull(list.getAdapter());
    assertEquals(ids, list.getAdapter().getItemCount());
  }

  @Test
  public void seasonPassAsksWithoutALimit() {
    // The manager reorders the whole list, so it cannot be a partial one.
    mTivo = FakeTivo.install();
    start(SeasonPass.class, null);

    JsonNode body = Utils.parseJson(Utils.stringifyToJson(
        mTivo.firstOfType("subscriptionSearch").getDataMap()));
    assertEquals(Fixtures.TSN, body.path("bodyId").asText());
    assertTrue("should ask for every season pass", body.path("noLimit")
        .asBoolean());
  }

  // --- Person ------------------------------------------------------------

  @Test
  public void personShowsTheNameItWasGivenAndAsksForTheDetails() {
    mTivo = FakeTivo.install()
        .answer("personSearch", Fixtures.response("personList"))
        .answer("collectionSearch", Fixtures.response("collectionList"));
    Person activity = start(Person.class, intentFor(Person.class,
        "personId", "tivo:pn.6781119", "fName", "William", "lName",
        "Shatner"));
    mTivo.deliver();

    assertEquals("William Shatner", activity.getTitle().toString());
    assertTrue(mTivo.sentTypes().contains("personSearch"));
    // The credits go out as a collectionSearch, like every other
    // collection-shaped search.
    assertEquals("two requests, both answered", 2, mTivo.sent().size());
  }

  @Test
  public void personFillsInTheBiographyFromTheResponse() {
    // The name is handed in so the header can draw before anything arrives;
    // the biography is the part that has to come out of the response.
    JsonNode person = Fixtures.response("personList").path("person").path(0);
    mTivo = FakeTivo.install()
        .answer("personSearch", Fixtures.response("personList"))
        .answer("collectionSearch", Fixtures.response("collectionList"));
    Person activity = start(Person.class, intentFor(Person.class,
        "personId", person.path("personId").asText(),
        "fName", person.path("first").asText(),
        "lName", person.path("last").asText()));
    mTivo.deliver();

    TextView name = activity.findViewById(R.id.person_name);
    TextView birthplace = activity.findViewById(R.id.person_birthplace);
    TextView birthdate = activity.findViewById(R.id.person_birthdate);
    assertNotNull("the screen should have swapped in its real layout", name);
    assertEquals("Sonequa Martin-Green", name.getText().toString());
    assertTrue("birthplace should come from the response: "
        + birthplace.getText(), birthplace.getText().toString()
        .contains(person.path("birthPlace").asText()));
    assertTrue("birthdate should be formatted, not raw: " + birthdate.getText(),
        birthdate.getText().toString().contains("1985"));
  }

  @Test
  public void personWithNoExtrasGivesUpRatherThanCrashing() {
    mTivo = FakeTivo.install();
    Person activity = start(Person.class, null);
    assertTrue("should have finished", activity.isFinishing());
  }

  // --- Upcoming ----------------------------------------------------------

  @Test
  public void upcomingListsTheOffersForACollection() {
    mTivo = FakeTivo.install()
        .answer("offerSearch", Fixtures.response("offerList"));
    Upcoming activity = start(Upcoming.class,
        intentFor(Upcoming.class, "collectionId", "tivo:cl.22439"));
    mTivo.deliver();

    assertEquals("Upcoming", activity.getTitle().toString());
    int offers = Fixtures.response("offerList").path("offer").size();
    ListView list = activity.getListView();
    assertEquals(offers, list.getAdapter().getCount());
  }

  @Test
  public void upcomingWithoutACollectionAsksForNothing() {
    mTivo = FakeTivo.install();
    start(Upcoming.class, null);
    assertTrue("should not have asked for offers", mTivo.sent().isEmpty());
  }

  // --- Now Showing -------------------------------------------------------

  @Test
  public void nowShowingAsksWhatIsPlayingAndSubscribesToPosition() {
    mTivo = FakeTivo.install()
        .answer("whatsOnSearch", Fixtures.response("whatsOnList"))
        .answer("bodyConfigSearch", Fixtures.response("bodyConfigList"))
        .answer("videoPlaybackInfoEventRegister",
            Fixtures.response("videoPlaybackInfoEvent"));
    start(NowShowing.class, null);
    mTivo.deliver();

    assertTrue(mTivo.sentTypes().contains("whatsOnSearch"));
    assertTrue(mTivo.sentTypes().contains("videoPlaybackInfoEventRegister"));
  }

  @Test
  public void nowShowingSurvivesAnIdleBox() {
    // Nothing playing: no recording and no offer to look up, which is the
    // state the captured whatsOnList was taken in.
    mTivo = FakeTivo.install()
        .answer("whatsOnSearch", Fixtures.response("whatsOnList"))
        .answer("bodyConfigSearch", Fixtures.response("bodyConfigList"))
        .answer("videoPlaybackInfoEventRegister",
            Fixtures.response("videoPlaybackInfoEvent"));
    NowShowing activity = start(NowShowing.class, null);
    mTivo.deliver();

    assertNotNull("the home tiles should be up",
        activity.findViewById(R.id.target_myshows));
    assertFalse("should not have given up", activity.isFinishing());
  }

  // --- Search ------------------------------------------------------------

  @Test
  public void searchAsksOnceTheTypingPauses() {
    mTivo = FakeTivo.install()
        .answer("unifiedItemSearch", Fixtures.response("unifiedItemList"));
    Search activity = start(Search.class, null);

    TextView box = activity.findViewById(R.id.search_box);
    box.setText("trek");
    assertTrue("nothing should go out until the typing stops",
        mTivo.sent().isEmpty());

    org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks();

    assertTrue("should have searched: " + mTivo.sentTypes(),
        mTivo.sentTypes().contains("unifiedItemSearch"));
    JsonNode body = Utils.parseJson(Utils.stringifyToJson(
        mTivo.firstOfType("unifiedItemSearch").getDataMap()));
    assertEquals("trek*", body.path("keyword").asText());
  }

  @Test
  public void searchFillsTheListWithWhatComesBack() {
    mTivo = FakeTivo.install()
        .answer("unifiedItemSearch", Fixtures.response("unifiedItemList"));
    Search activity = start(Search.class, null);

    TextView box = activity.findViewById(R.id.search_box);
    box.setText("trek");
    org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks();
    mTivo.deliver();

    int results =
        Fixtures.response("unifiedItemList").path("unifiedItem").size();
    assertEquals(results, activity.getListView().getAdapter().getCount());
  }

  @Test
  public void clearingTheSearchBoxEmptiesTheList() {
    mTivo = FakeTivo.install()
        .answer("unifiedItemSearch", Fixtures.response("unifiedItemList"));
    Search activity = start(Search.class, null);
    TextView box = activity.findViewById(R.id.search_box);

    box.setText("trek");
    org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks();
    mTivo.deliver();
    assertTrue(activity.getListView().getAdapter().getCount() > 0);

    box.setText("");
    org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks();
    assertEquals(0, activity.getListView().getAdapter().getCount());
  }

  // --- To Do -------------------------------------------------------------

  @Test
  public void toDoAsksForWhatIsScheduledOrRecording() {
    mTivo = FakeTivo.install()
        .answer("recordingSearch", Fixtures.response("idSequence"))
        .answer("recordingSearch", Fixtures.response("recordingList"));
    ToDo activity = start(ToDo.class, null);
    mTivo.deliver();

    JsonNode body = Utils.parseJson(Utils.stringifyToJson(
        mTivo.firstOfType("recordingSearch").getDataMap()));
    assertEquals("idSequence", body.path("format").asText());
    assertEquals("inProgress", body.path("state").path(0).asText());
    assertEquals("scheduled", body.path("state").path(1).asText());
    assertEquals("To Do List", activity.getTitle().toString());
  }

  @Test
  public void toDoGetsARowPerScheduledRecording() {
    mTivo = FakeTivo.install()
        .answer("recordingSearch", Fixtures.response("idSequence"))
        .answer("recordingSearch", Fixtures.response("recordingList"));
    ToDo activity = start(ToDo.class, null);
    mTivo.deliver();

    int ids = Fixtures.response("idSequence").path("objectIdAndType").size();
    // Unlike My Shows there is no extra "Recently Deleted" row here.
    assertEquals(ids, activity.getListView().getAdapter().getCount());
  }
}
