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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

import android.app.AlertDialog;
import android.content.Intent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowDialog;

import com.fasterxml.jackson.databind.node.ObjectNode;

import com.arantius.tivocommander.rpc.FakeTivo;
import com.arantius.tivocommander.rpc.request.GridRowSearch;
import com.arantius.tivocommander.views.GuideScrollSync;
import com.arantius.tivocommander.views.SyncedHorizontalScrollView;
import com.fasterxml.jackson.databind.JsonNode;

/** The guide grid, driven against recorded responses. */
@RunWith(RobolectricTestRunner.class)
public class GuideTest {
  private ActivityController<Guide> mController;
  private FakeTivo mTivo;
  private TimeZone mZone;

  /**
   * Run in the zone the capture was taken in.
   *
   * The grid draws one local day at a time, so where the captured listings
   * fall within that day depends on the zone.  They start at 21:00 UTC: mid
   * afternoon in Chicago, but in UTC -- which is what CI runs in -- so late
   * that the span is clamped against midnight and reaches five hours back
   * instead of two, and the offers past midnight are never drawn.
   */
  @Before
  public void setUp() {
    mZone = TimeZone.getDefault();
    TimeZone.setDefault(TimeZone.getTimeZone("America/Chicago"));
  }

  @After
  public void tearDown() {
    TimeZone.setDefault(mZone);
    if (mController != null) {
      mController.close();
    }
    FakeTivo.uninstall();
    // The lineup cache is static and per-process, so without this the first
    // test to read a lineup hands it to every test after it -- and those
    // start from the cache instead of discovering it, which is a different
    // code path from the one they mean to drive.
    ChannelCache.clearAll(RuntimeEnvironment.getApplication());
  }

  /**
   * The captured To Do page, which is the trimmed shape the guide asks for:
   * offer ids and recording ids and nothing else.  Picked by shape rather than
   * by number so renumbering the recordingList variants cannot silently hand
   * this test the My Shows capture instead.
   */
  private static JsonNode scheduledFixture() {
    for (JsonNode response : Fixtures.responses("recordingList")) {
      JsonNode first = response.path("recording").path(0);
      if (first.has("offerId") && !first.has("title")) {
        return response;
      }
    }
    throw new AssertionError("no captured recordingList of To Do shape");
  }

  /**
   * The first moment the captured page has listings for.
   *
   * The screen is opened on this rather than on "now", so the capture keeps
   * lining up with the window the screen asks for however long ago it was
   * taken.
   */
  private static long captureStart() {
    long earliest = Long.MAX_VALUE;
    for (JsonNode row : Fixtures.response("gridRowList").path("gridRow")) {
      for (JsonNode offer : row.path("offer")) {
        java.util.Date start =
            Utils.parseDateTimeStr(offer.path("startTime").asText());
        if (start != null && start.getTime() < earliest) {
          earliest = start.getTime();
        }
      }
    }
    assertTrue("capture has no listings", earliest < Long.MAX_VALUE);
    return earliest;
  }

  /**
   * An answer with no rows: the end of the lineup.
   *
   * The screen pages until a page brings nothing new, so without this the
   * FakeTivo would keep re-serving the same capture and the channel list
   * would grow forever.  A real box answers the same way past the last
   * channel.
   */
  private static JsonNode endOfLineup() {
    com.fasterxml.jackson.databind.node.ObjectNode body =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            Fixtures.response("gridRowList").deepCopy();
    body.putArray("gridRow");
    return body;
  }

  private Guide start() {
    return startAt(captureStart());
  }

  /**
   * Open the grid on a particular moment.
   *
   * Which moment matters for more than the listings now: it also fixes which
   * day the screen counts as today, and so how far back it will scroll.
   */
  private Guide startAt(long when) {
    mTivo = FakeTivo.install()
        .answer("recordingSearch", scheduledFixture())
        .answer("gridRowSearch", Fixtures.response("gridRowList"))
        .answer("gridRowSearch", endOfLineup());
    return open(when);
  }

  /** Launch the screen against whatever FakeTivo is already installed. */
  private Guide open(long when) {
    Intent intent =
        new Intent(RuntimeEnvironment.getApplication(), Guide.class);
    intent.putExtra(Guide.EXTRA_START_TIME, when);
    mController = Robolectric.buildActivity(Guide.class, intent).setup();
    return mController.get();
  }

  /** The body of the first request of a type, as it went out. */
  private JsonNode firstBody(String reqType) {
    return Utils.parseJson(
        Utils.stringifyToJson(mTivo.firstOfType(reqType).getDataMap()));
  }

  /** The local clock time on the day the capture was taken. */
  private Calendar captureDayAt(int hour, int minute) {
    Calendar when = Calendar.getInstance();
    when.setTimeInMillis(captureStart());
    when.set(Calendar.HOUR_OF_DAY, hour);
    when.set(Calendar.MINUTE, minute);
    when.set(Calendar.SECOND, 0);
    when.set(Calendar.MILLISECOND, 0);
    return when;
  }

  /**
   * Give the screen a size and lay it out.
   *
   * Nothing does this on its own in a unit test, and until it happens a
   * RecyclerView has bound no rows at all -- so anything reading a row view
   * has to ask for it first.
   */
  private static void layOut(Guide activity) {
    layOut(activity, 1080, 1920);
  }

  private static void layOut(Guide activity, int width, int height) {
    View root = activity.findViewById(android.R.id.content);
    root.measure(
        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
    root.layout(0, 0, width, height);
  }

  /** Every program block currently built into the grid. */
  private static List<View> blocks(Guide activity) {
    List<View> out = new ArrayList<View>();
    collectBlocks(activity.findViewById(R.id.guide_rows), out);
    return out;
  }

  private static void collectBlocks(View view, List<View> out) {
    if (view instanceof FrameLayout && view.getId() == R.id.guide_row_blocks) {
      FrameLayout blocks = (FrameLayout) view;
      for (int i = 0; i < blocks.getChildCount(); i++) {
        out.add(blocks.getChildAt(i));
      }
      return;
    }
    if (view instanceof android.view.ViewGroup) {
      android.view.ViewGroup group = (android.view.ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        collectBlocks(group.getChildAt(i), out);
      }
    }
  }

  @Test
  public void itAsksForListingsAndForWhatIsScheduled() {
    start();
    assertTrue("should ask for listings: " + mTivo.sentTypes(),
        mTivo.sentTypes().contains("gridRowSearch"));
    assertTrue("should ask what is already scheduled: " + mTivo.sentTypes(),
        mTivo.sentTypes().contains("recordingSearch"));
  }

  @Test
  public void theFirstPageHasNoAnchorSoItStartsAtTheTopOfTheLineup() {
    start();
    JsonNode body = firstBody("gridRowSearch");
    assertFalse("the first page starts at the top of the lineup",
        body.has("anchorChannelIdentifier"));
    // Without this the box returns channels the tuner cannot receive.
    assertEquals("true", body.path("isReceived").asText());
    assertEquals(GridRowSearch.PAGE_SIZE, body.path("count").asInt());
  }

  @Test
  public void theWindowIsSentAsUtcWithNoZoneMarker() {
    start();
    JsonNode body = firstBody("gridRowSearch");
    String from = body.path("minEndTime").asText();
    String to = body.path("maxStartTime").asText();
    assertTrue("minEndTime was " + from,
        from.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
    assertNotNull(Utils.parseDateTimeStr(from));
    // The span the screen opens on: a couple of hours behind the moment
    // asked for and six ahead of it, which is what makes the grid
    // scrollable both ways rather than a single screenful.
    long hours = (Utils.parseDateTimeStr(to).getTime()
        - Utils.parseDateTimeStr(from).getTime()) / 3600000L;
    assertEquals(8, hours);
  }

  @Test
  public void theSpanReachesBackBehindTheMomentTheGridOpensOn() {
    start();
    Date from = Utils.parseDateTimeStr(
        firstBody("gridRowSearch").path("minEndTime").asText());
    // Without hours already loaded behind it, a row sitting at its own
    // left edge cannot be dragged that way at all, and the earlier part
    // of the day would be out of reach.
    assertEquals("two hours of room to start scrolling back into",
        captureStart() - 2 * 3600000L, from.getTime());
  }

  @Test
  public void theSpanDoesNotReachBackPastTheStartOfToday() {
    startAt(captureDayAt(0, 10).getTimeInMillis());
    Date from = Utils.parseDateTimeStr(
        firstBody("gridRowSearch").path("minEndTime").asText());
    assertEquals("the grid stops at the start of today, not two hours"
        + " before it", captureDayAt(0, 0).getTime(), from);
  }

  @Test
  public void aRefusedChannelPageDoesNotEndChannelPagingForGood() {
    // An error is not the end of the lineup.  Treating it as one used to
    // leave scrolling down silently dead for the life of the screen.
    mTivo = FakeTivo.install()
        .answer("recordingSearch", scheduledFixture())
        .answer("gridRowSearch", Utils.parseJson(
            "{\"type\": \"error\", \"code\": \"badRequest\","
                + " \"text\": \"no\"}"))
        .answer("gridRowSearch", Fixtures.response("gridRowList"));
    Intent intent =
        new Intent(RuntimeEnvironment.getApplication(), Guide.class);
    intent.putExtra(Guide.EXTRA_START_TIME, captureStart());
    mController = Robolectric.buildActivity(Guide.class, intent).setup();
    Guide activity = mController.get();
    mTivo.deliver();
    layOut(activity);

    // Nothing loaded, so there is nothing to scroll to prompt a retry: the
    // screen has to say the read failed rather than sit blank or, worse,
    // claim the lineup is empty.
    TextView empty = activity.findViewById(R.id.guide_empty);
    assertEquals(View.VISIBLE, empty.getVisibility());
    assertEquals(activity.getString(R.string.guide_failed),
        empty.getText().toString());
  }

  @Test
  public void everyChannelInTheAnswerBecomesARow() {
    Guide activity = start();
    mTivo.deliver();

    int rows = Fixtures.response("gridRowList").path("gridRow").size();
    assertEquals(rows, activity.findViewById(R.id.guide_rows) == null ? -1
        : ((androidx.recyclerview.widget.RecyclerView)
            activity.findViewById(R.id.guide_rows)).getAdapter()
                .getItemCount());
  }

  @Test
  public void aChannelRowIsLabelledWithItsNumberAndCallSign() {
    Guide activity = start();
    mTivo.deliver();
    layOut(activity);

    JsonNode channel =
        Fixtures.response("gridRowList").path("gridRow").path(0)
            .path("channel");
    TextView label = activity.findViewById(R.id.guide_channel);
    assertNotNull("the first row should be laid out", label);
    assertTrue("row label was " + label.getText(),
        label.getText().toString()
            .contains(channel.path("channelNumber").asText()));
    assertTrue("row label was " + label.getText(),
        label.getText().toString()
            .contains(channel.path("callSign").asText()));
  }

  @Test
  public void programsThatWillRecordAreMarkedAndTheRestAreNot() {
    Guide activity = start();
    mTivo.deliver();
    layOut(activity);

    // The offer ids the captured To Do page says are scheduled.
    List<String> scheduled = new ArrayList<String>();
    for (JsonNode recording : scheduledFixture().path("recording")) {
      scheduled.add(recording.path("offerId").asText());
    }

    int marked = 0;
    int plain = 0;
    for (View block : blocks(activity)) {
      String description = String.valueOf(block.getContentDescription());
      if (description.contains(
          activity.getString(R.string.a11y_scheduled))) {
        marked++;
      } else {
        plain++;
      }
    }
    // The capture was taken around channels that have scheduled recordings,
    // so the grid must show both kinds.
    assertTrue("no block was marked as scheduled", marked > 0);
    assertTrue("every block was marked as scheduled", plain > 0);

    // And the marks are the scheduled ones, not just any.
    int expected = 0;
    for (JsonNode row : Fixtures.response("gridRowList").path("gridRow")) {
      for (JsonNode offer : row.path("offer")) {
        if (scheduled.contains(offer.path("offerId").asText())) {
          expected++;
        }
      }
    }
    assertEquals("marked blocks should be exactly the scheduled offers",
        expected, marked);
  }

  @Test
  public void aBlockIsAsWideAsTheProgramIsLong() {
    Guide activity = start();
    mTivo.deliver();
    layOut(activity);

    int minuteWidth = activity.getResources()
        .getDimensionPixelSize(R.dimen.guide_minute_width);
    int gap = activity.getResources()
        .getDimensionPixelSize(R.dimen.guide_block_gap);

    JsonNode row = Fixtures.response("gridRowList").path("gridRow").path(0);
    List<View> found = blocks(activity);
    assertTrue(found.size() > 0);

    // Match the first block by its description rather than by index: an offer
    // already running at the left edge is clipped, so the first offer in the
    // capture need not be the first block drawn.
    boolean checked = false;
    for (JsonNode offer : row.path("offer")) {
      long minutes = offer.path("duration").asLong() / 60;
      for (View block : found) {
        String description = String.valueOf(block.getContentDescription());
        if (!description.startsWith(offer.path("title").asText())) {
          continue;
        }
        FrameLayout.LayoutParams params =
            (FrameLayout.LayoutParams) block.getLayoutParams();
        // Clipped blocks are narrower than the program; full ones are exact.
        if (params.leftMargin > 0) {
          assertEquals("block width for " + offer.path("title").asText(),
              minutes * minuteWidth - gap, params.width);
          checked = true;
        }
        break;
      }
      if (checked) {
        break;
      }
    }
    assertTrue("no uncliped block found to measure", checked);
  }

  @Test
  public void everyRowScrollsToTheSamePlace() {
    Guide activity = start();
    mTivo.deliver();

    GuideScrollSync sync = new GuideScrollSync();
    final int[] moved = new int[] { -1 };
    GuideScrollSync.Member follower = new GuideScrollSync.Member() {
      public void setSyncedScrollX(int scrollX) {
        moved[0] = scrollX;
      }
    };
    final boolean[] joined = new boolean[] { false };
    GuideScrollSync.Member leader = new GuideScrollSync.Member() {
      public void setSyncedScrollX(int scrollX) {
        if (joined[0]) {
          throw new AssertionError("the row that moved should not be told to");
        }
      }
    };
    sync.register(leader);
    joined[0] = true;
    sync.register(follower);

    sync.onMemberScrolled(leader, 480);
    assertEquals("the other rows follow", 480, moved[0]);
    assertEquals(480, sync.getScrollX());

    // A row bound after the grid has been scrolled catches up on joining,
    // which is what stops a recycled row showing the wrong hour.
    final int[] late = new int[] { -1 };
    sync.register(new GuideScrollSync.Member() {
      public void setSyncedScrollX(int scrollX) {
        late[0] = scrollX;
      }
    });
    assertEquals(480, late[0]);
  }

  @Test
  public void aRowThatCannotTakeThePositionYetDoesNotDragTheGroupBack() {
    GuideScrollSync sync = new GuideScrollSync();
    final int[] settled = new int[] { -1 };
    sync.register(new GuideScrollSync.Member() {
      public void setSyncedScrollX(int scrollX) {
        settled[0] = scrollX;
      }
    });
    sync.onMemberScrolled(new GuideScrollSync.Member() {
      public void setSyncedScrollX(int scrollX) {
      }
    }, 960);
    assertEquals(960, sync.getScrollX());

    // A row joining before it has been measured reports the zero it clamped
    // to.  Registering has to be deaf to that, or the whole grid snaps back
    // to the start of the span whenever one row is re-bound.
    sync.register(new GuideScrollSync.Member() {
      public void setSyncedScrollX(int scrollX) {
        sync.onMemberScrolled(this, 0);
      }
    });
    assertEquals("a joining row must not move the group", 960,
        sync.getScrollX());
    assertEquals("and must not drag the rows already in place back", 960,
        settled[0]);
  }

  @Test
  public void rebuildingTheRulerDoesNotSnapTheGridToTheStartOfTheSpan() {
    Guide activity = start();
    mTivo.deliver();
    layOut(activity);

    SyncedHorizontalScrollView ruler =
        activity.findViewById(R.id.guide_ruler_scroll);
    ruler.setSyncedScrollX(720);
    assertEquals(720, ruler.getSyncedScrollX());

    // Emptying the ruler is what loading another six hours does; the clamp
    // that causes used to be relayed to every row as a scroll back to zero.
    ((android.widget.LinearLayout) activity.findViewById(R.id.guide_ruler))
        .removeAllViews();
    layOut(activity);

    assertEquals("emptying the ruler must not move the group", 720,
        ruler.getSyncedScrollX());
  }

  /** How many channels the grid is currently showing. */
  private static int rowCount(Guide activity) {
    androidx.recyclerview.widget.RecyclerView list =
        activity.findViewById(R.id.guide_rows);
    return list == null || list.getAdapter() == null ? -1
        : list.getAdapter().getItemCount();
  }

  @Test
  public void theHeaderNamesTheDayAndScrollingDoesNotChangeIt() {
    // Opened late in the evening: under the old window-shaped grid this was
    // the case where the loaded hours ran across midnight and the header had
    // to follow them.  The grid is one day wide now, so there is nowhere to
    // scroll that is not the day the header names, and the picker is the only
    // thing that changes it.
    Guide activity = startAt(captureDayAt(23, 0).getTimeInMillis());
    mTivo.deliver();

    TextView header = activity.findViewById(R.id.guide_day);
    String opening = header.getText().toString();
    assertTrue("the header should name the opening day: " + opening,
        opening.contains("/" + captureDayAt(23, 0).get(Calendar.DAY_OF_MONTH)));

    // Scroll positions are measured from midnight now, so this is four in the
    // morning of the same day -- not four hours along from where it opened.
    int minuteWidth = activity.getResources()
        .getDimensionPixelSize(R.dimen.guide_minute_width);
    activity.setSyncedScrollX(4 * 60 * minuteWidth);

    assertEquals("the day on screen cannot change by scrolling", opening,
        header.getText().toString());
  }

  @Test
  public void theGridIsOneWholeDayWideWhateverIsLoaded() {
    // The invariant the rest of it rests on: a row is as wide as the day, not
    // as wide as the listings that happen to have arrived.  Only a few hours
    // around 8pm are fetched at the open, and the row is still 24 hours over.
    Guide activity = startAt(captureDayAt(20, 0).getTimeInMillis());
    mTivo.deliver();
    layOut(activity);

    int minuteWidth = activity.getResources()
        .getDimensionPixelSize(R.dimen.guide_minute_width);
    androidx.recyclerview.widget.RecyclerView list =
        activity.findViewById(R.id.guide_rows);
    View blocks = list.getChildAt(0).findViewById(R.id.guide_row_blocks);
    assertNotNull("expected a row to have been bound", blocks);
    assertEquals("a row should span the whole day",
        24 * 60 * minuteWidth, blocks.getLayoutParams().width);
  }

  @Test
  public void theLineupIsKeptSoTheNextOpenNeedNotDiscoverItAgain() {
    start();
    mTivo.deliver();

    int discovered = Fixtures.response("gridRowList").path("gridRow").size();
    List<JsonNode> kept =
        ChannelCache.get(RuntimeEnvironment.getApplication(), Fixtures.TSN);
    assertNotNull("the lineup should have been kept", kept);
    assertEquals(discovered, kept.size());

    mController.close();
    mController = null;
    FakeTivo.uninstall();

    // Opening again draws the channel column from what was kept, before the
    // box has answered anything at all.
    mTivo = FakeTivo.install()
        .answer("recordingSearch", scheduledFixture())
        .answer("gridRowSearch", Fixtures.response("gridRowList"));
    Guide again = open(captureStart());
    assertEquals("the channels are there before the box answers",
        discovered, rowCount(again));
    // And the listings are asked for by anchoring on a row it already holds,
    // rather than by walking the lineup from the top all over again.
    assertTrue("a cached start anchors at a channel it already knows",
        firstBody("gridRowSearch").has("anchorChannelIdentifier"));
  }

  @Test
  public void aLineupThatNoLongerMatchesIsThrownAwayAndReadAgain() {
    start();
    mTivo.deliver();
    assertNotNull(
        ChannelCache.get(RuntimeEnvironment.getApplication(), Fixtures.TSN));

    mController.close();
    mController = null;
    FakeTivo.uninstall();

    // The same shape with different channels: the service changed the lineup
    // while the guide was not looking.
    ObjectNode changed =
        (ObjectNode) Fixtures.response("gridRowList").deepCopy();
    for (JsonNode row : changed.path("gridRow")) {
      ObjectNode channel = (ObjectNode) row.path("channel");
      channel.put("stationId",
          "tivo:st.9" + channel.path("channelNumber").asText());
    }
    mTivo = FakeTivo.install()
        .answer("recordingSearch", scheduledFixture())
        .answer("gridRowSearch", changed);
    open(captureStart());
    mTivo.deliver();

    List<JsonNode> now =
        ChannelCache.get(RuntimeEnvironment.getApplication(), Fixtures.TSN);
    assertNotNull("a changed lineup is read again, not merely dropped", now);
    assertEquals("and what is kept is the lineup the box now has",
        "tivo:st.92-1", now.get(0).path("stationId").asText());
  }

  @Test
  public void aLongPressOffersTheDetailsAsWellAsRecording() {
    Guide activity = start();
    mTivo.deliver();
    layOut(activity);

    List<View> found = blocks(activity);
    assertTrue("nothing to press", found.size() > 0);
    assertTrue(found.get(0).performLongClick());

    AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
    assertNotNull("a long press should open a menu", dialog);
    List<String> choices = new ArrayList<String>();
    for (int i = 0; i < dialog.getListView().getCount(); i++) {
      choices.add(String.valueOf(dialog.getListView().getItemAtPosition(i)));
    }
    // Recording and reading about it: a long press that could only record was
    // a trap, since the two gestures are easy to confuse on small blocks.
    assertEquals("recording and the details, and nothing else: " + choices,
        2, choices.size());
    assertTrue("no way to read about the program: " + choices,
        choices.contains(activity.getString(R.string.guide_show_info)));
  }

  @Test
  public void pickingADayMovesToTheSameTimeOfDayOnIt() {
    Guide activity = start();
    mTivo.deliver();

    Date openedOn = Utils.parseDateTimeStr(
        firstBody("gridRowSearch").path("minEndTime").asText());

    assertTrue(activity.findViewById(R.id.guide_day).performClick());
    AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
    assertNotNull("the date should open a day picker", dialog);
    assertEquals("today and the twelve days after it",
        13, dialog.getListView().getCount());

    int before = mTivo.sent().size();
    dialog.getListView().performItemClick(null, 2, 2);
    assertTrue("picking a day should ask for that day's listings",
        mTivo.sent().size() > before);

    JsonNode body = Utils.parseJson(
        Utils.stringifyToJson(mTivo.sent().get(before).getDataMap()));
    Date asked = Utils.parseDateTimeStr(body.path("minEndTime").asText());
    Calendar wanted = Calendar.getInstance();
    wanted.setTime(openedOn);
    wanted.add(Calendar.DAY_OF_MONTH, 2);
    // The whole window moves two days on, the time of day untouched: picking
    // Friday while reading Tuesday evening means Friday evening.
    assertEquals("two days on, at the same time of day",
        wanted.getTime(), asked);
  }

  @Test
  public void aFirstShowingIsBadgedAndARepeatIsNot() {
    Guide activity = start();
    mTivo.deliver();
    layOut(activity);

    // The rule My Shows uses, read off the capture: an episode that is not a
    // repeat.  ("isNew" is asked for, but a real box does not send it.)
    // Keyed by title and start time, and any key the capture uses for both a
    // first showing and a repeat is dropped, so a block can be matched back to
    // its offer without ambiguity.
    SimpleDateFormat clock = new SimpleDateFormat("h:mm a", Locale.US);
    Map<String, Boolean> byKey = new HashMap<String, Boolean>();
    Set<String> ambiguous = new HashSet<String>();
    for (JsonNode row : Fixtures.response("gridRowList").path("gridRow")) {
      for (JsonNode offer : row.path("offer")) {
        Date at = Utils.parseDateTimeStr(offer.path("startTime").asText());
        String key = offer.path("title").asText() + "|" + clock.format(at);
        boolean fresh = offer.path("episodic").asBoolean()
            && !offer.path("repeat").asBoolean();
        Boolean seen = byKey.put(key, fresh);
        if (seen != null && seen.booleanValue() != fresh) {
          ambiguous.add(key);
        }
      }
    }

    int badged = 0;
    int plain = 0;
    for (View block : blocks(activity)) {
      String description = String.valueOf(block.getContentDescription());
      TextView detail = block.findViewById(R.id.guide_offer_detail);
      boolean hasBadge = detail.getCompoundDrawables()[0] != null;
      // Whatever is drawn has to be said too, or the badge is invisible to
      // anyone using a screen reader.
      assertEquals("the badge and the spoken description must agree: "
          + description, hasBadge,
          description.contains(activity.getString(R.string.a11y_badge_new)));

      for (Map.Entry<String, Boolean> offer : byKey.entrySet()) {
        String key = offer.getKey();
        if (ambiguous.contains(key)) {
          continue;
        }
        String title = key.substring(0, key.indexOf('|'));
        String at = ", " + key.substring(key.indexOf('|') + 1) + " to ";
        if (!description.startsWith(title) || !description.contains(at)) {
          continue;
        }
        assertEquals("badge on " + key, offer.getValue().booleanValue(),
            hasBadge);
        if (hasBadge) {
          badged++;
        } else {
          plain++;
        }
        break;
      }
    }
    // The capture holds both kinds, so the badge has to be telling them apart
    // rather than being on everything or on nothing.
    assertTrue("no block was badged as new", badged > 0);
    assertTrue("every block was badged as new", plain > 0);
  }

  @Test
  public void aMemberThatMovesTheGroupFromInsideBeingToldWhereToSitWins() {
    final GuideScrollSync sync = new GuideScrollSync();
    // Stands in for the guide itself, which moves the whole group from inside
    // setSyncedScrollX: that is what giving hours back at the start of the
    // span does.
    final boolean[] moved = new boolean[] { false };
    sync.register(new GuideScrollSync.Member() {
      public void setSyncedScrollX(int scrollX) {
        if (scrollX == 500 && !moved[0]) {
          moved[0] = true;
          sync.setScrollX(900);
        }
      }
    });
    final int[] last = new int[] { -1 };
    sync.register(new GuideScrollSync.Member() {
      public void setSyncedScrollX(int scrollX) {
        last[0] = scrollX;
      }
    });

    sync.setScrollX(500);
    assertEquals(900, sync.getScrollX());
    // The outer pass must not carry on handing out the position the group has
    // already moved off: everything it had yet to reach would be left behind
    // it, showing a different hour from the rest of the grid.
    assertEquals("a row must not be left at the abandoned position",
        900, last[0]);
  }

  @Test
  public void aRefusedBackwardExtendDoesNotRetryForEver() {
    mTivo = FakeTivo.install()
        .answer("recordingSearch", scheduledFixture())
        .answer("gridRowSearch", Fixtures.response("gridRowList"))
        .answer("gridRowSearch", Utils.parseJson(
            "{\"type\": \"error\", \"code\": \"badRequest\","
                + " \"text\": \"no\"}"));
    Guide activity = open(captureStart());
    mTivo.deliver();
    // Narrow, so that arriving at the start of the span asks for the hours
    // before it without also asking for the ones after it.
    layOut(activity, 200, 1920);

    SyncedHorizontalScrollView ruler =
        activity.findViewById(R.id.guide_ruler_scroll);
    // A position reported by a member, not handed to one: that is a real
    // scroll, and it arms the backward prefetch.  Ending at the very start of
    // the loaded span is what asks for the hours before it.
    ruler.scrollTo(240, 0);
    ruler.scrollTo(0, 0);

    int before = mTivo.sentTypes().size();
    // Putting the span back moves the grid, and moving the grid is what asks
    // for more hours -- so the rollback used to re-issue the refusal that
    // caused it, for ever.  deliver() gives up after twenty rounds of that.
    mTivo.deliver();
    int asked = mTivo.sentTypes().size() - before;
    assertTrue("a refused extend kept asking: " + asked + " requests",
        asked < 10);
  }
}
