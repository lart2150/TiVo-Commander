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
import static org.junit.Assert.assertNotEquals;

import android.content.Intent;
import android.view.View;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

import com.arantius.tivocommander.rpc.FakeTivo;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Each screen, answered with an error body instead of data.
 *
 * An error arrives at the same listener as a real answer, and nearly every
 * field of it reads as missing, so a screen that does not ask shows "you have
 * none" -- or, where it parses a date out of the missing field, crashes.
 */
@RunWith(RobolectricTestRunner.class)
public class ErrorResponseTest {
  private static final JsonNode ERROR = Utils.parseJson(
      "{\"type\": \"error\", \"code\": \"badRequest\","
          + " \"text\": \"refused for the test\"}");

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

  private static String string(int id) {
    return RuntimeEnvironment.getApplication().getString(id);
  }

  @Test
  public void myShowsStaysUpAndSaysSoWhenTheListIsRefused() {
    // This used to fall into the empty-folder case and close the screen,
    // which at the top level is leaving the app without a word.
    mTivo = FakeTivo.install()
        .answer("bodyConfigSearch", Fixtures.response("bodyConfigList"))
        .answer("recordingFolderItemSearch", ERROR);
    MyShows activity = start(MyShows.class, null);
    mTivo.deliver();

    assertFalse("should not have given up", activity.isFinishing());
    assertEquals(0, activity.getListView().getAdapter().getCount());
    assertEquals(string(R.string.error_list_failed),
        ShadowToast.getTextOfLatestToast());
  }

  @Test
  public void myShowsDrawsNoMeterFromARefusedConfig() {
    mTivo = FakeTivo.install()
        .answer("bodyConfigSearch", ERROR)
        .answer("recordingFolderItemSearch", Fixtures.response("idSequence"))
        .answer("recordingFolderItemSearch",
            Fixtures.response("recordingFolderItemList"));
    MyShows activity = start(MyShows.class, null);
    mTivo.deliver();

    TextView meterText = activity.findViewById(R.id.meter_text);
    assertNotEquals("0% Disk Used", meterText.getText().toString());
  }

  @Test
  public void toDoDoesNotClaimNothingIsScheduled() {
    mTivo = FakeTivo.install().answer("recordingSearch", ERROR);
    ToDo activity = start(ToDo.class, null);
    mTivo.deliver();

    assertEquals(0, activity.getListView().getAdapter().getCount());
    assertEquals(string(R.string.error_list_failed),
        ShadowToast.getTextOfLatestToast());
  }

  @Test
  public void toDoRowsWhoseDetailsAreRefusedAreNotAskedForAgainAndAgain() {
    mTivo = FakeTivo.install()
        .answer("recordingSearch", Fixtures.response("idSequence"))
        .answer("recordingSearch", ERROR);
    ToDo activity = start(ToDo.class, null);
    // deliver() throws if answers keep setting off new requests; putting the
    // failed rows back to MISSING would ask again on every redraw.
    mTivo.deliver();
    ShadowLooper.runUiThreadTasksIncludingDelayedTasks();
    mTivo.deliver();

    int ids = Fixtures.response("idSequence").path("objectIdAndType").size();
    assertEquals("the rows stay, still loading", ids,
        activity.getListView().getAdapter().getCount());
    assertEquals(1, ShadowToast.shownToastCount());
  }

  @Test
  public void searchDoesNotSayNothingMatched() {
    mTivo = FakeTivo.install().answer("unifiedItemSearch", ERROR);
    Search activity = start(Search.class, null);
    TextView box = activity.findViewById(R.id.search_box);
    box.setText("trek");
    ShadowLooper.runUiThreadTasksIncludingDelayedTasks();
    mTivo.deliver();

    assertEquals(0, activity.getListView().getAdapter().getCount());
    assertEquals("the empty view would read as no matches", View.INVISIBLE,
        activity.findViewById(android.R.id.empty).getVisibility());
    assertEquals(string(R.string.error_load_failed),
        ShadowToast.getTextOfLatestToast());
  }

  @Test
  public void upcomingDoesNotSayNothingIsComingUp() {
    mTivo = FakeTivo.install().answer("offerSearch", ERROR);
    Upcoming activity = start(Upcoming.class,
        intentFor(Upcoming.class, "collectionId", "tivo:cl.22439"));
    mTivo.deliver();

    assertNotEquals(View.VISIBLE,
        activity.findViewById(android.R.id.empty).getVisibility());
    assertEquals(string(R.string.error_list_failed),
        ShadowToast.getTextOfLatestToast());
  }

  @Test
  public void personSaysTheLookupFailed() {
    mTivo = FakeTivo.install()
        .answer("personSearch", ERROR)
        .answer("collectionSearch", Fixtures.response("collectionList"));
    Person activity = start(Person.class, intentFor(Person.class,
        "personId", "tivo:pn.6781119", "fName", "William", "lName",
        "Shatner"));
    mTivo.deliver();

    assertEquals(string(R.string.error_load_failed),
        ShadowToast.getTextOfLatestToast());
  }

  @Test
  public void seasonPassDoesNotClaimThereAreNone() {
    mTivo = FakeTivo.install().answer("subscriptionSearch", ERROR);
    SeasonPass activity = start(SeasonPass.class, null);
    mTivo.deliver();

    RecyclerView list = activity.findViewById(R.id.season_pass_list);
    assertEquals(0, list.getAdapter().getItemCount());
    assertEquals(string(R.string.error_list_failed),
        ShadowToast.getTextOfLatestToast());
  }

  @Test
  public void seasonPassKeepsItsRowsWhenTheDetailsAreRefused() {
    mTivo = FakeTivo.install()
        .answer("subscriptionSearch", Fixtures.response("idSequence"))
        .answer("subscriptionSearch", ERROR);
    SeasonPass activity = start(SeasonPass.class, null);
    mTivo.deliver();

    assertEquals(string(R.string.error_list_failed),
        ShadowToast.getTextOfLatestToast());
    assertFalse(activity.isFinishing());
  }

  @Test
  public void nowShowingSurvivesEveryAnswerBeingRefused() {
    mTivo = FakeTivo.install()
        .answer("whatsOnSearch", ERROR)
        .answer("bodyConfigSearch", ERROR)
        .answer("videoPlaybackInfoEventRegister", ERROR);
    NowShowing activity = start(NowShowing.class, null);
    mTivo.deliver();

    assertNotNull(activity.findViewById(R.id.target_myshows));
    assertFalse(activity.isFinishing());
  }

  @Test
  public void exploreDrawsTheShowWhenItsRecordingIsRefused() {
    // The recording's times used to be parsed out of the error body, and an
    // empty date string crashed the page.
    mTivo = FakeTivo.install()
        .answer("recordingSearch", Fixtures.response("recordingList"))
        .answer("recordingSearch", ERROR)
        .answer("subscriptionSearch", Fixtures.response("subscriptionList"));
    ExploreTabs activity = start(ExploreTabs.class,
        intentFor(ExploreTabs.class, "collectionId", "tivo:cl.22439",
            "recordingId", "tivo:rc.1"));
    ShadowLooper.runUiThreadTasksIncludingDelayedTasks();
    mTivo.deliver();
    ShadowLooper.runUiThreadTasksIncludingDelayedTasks();

    TextView title = activity.findViewById(R.id.content_title);
    assertNotNull("the page should still have drawn", title);
    assertEquals(View.GONE,
        activity.findViewById(R.id.content_air_time).getVisibility());
  }

  @Test
  public void exploreLeavesWhenTheContentItselfIsRefused() {
    mTivo = FakeTivo.install().answer("collectionSearch", ERROR);
    ExploreTabs activity = start(ExploreTabs.class,
        intentFor(ExploreTabs.class, "collectionId", "tivo:cl.22439"));
    ShadowLooper.runUiThreadTasksIncludingDelayedTasks();
    mTivo.deliver();
    ShadowLooper.runUiThreadTasksIncludingDelayedTasks();

    assertEquals(string(R.string.error_load_failed),
        ShadowToast.getTextOfLatestToast());
  }
}
