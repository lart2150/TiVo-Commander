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

import java.util.ArrayList;
import java.util.List;

import android.content.Intent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;

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

  @After
  public void tearDown() {
    if (mController != null) {
      mController.close();
    }
    FakeTivo.uninstall();
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
    mTivo = FakeTivo.install()
        .answer("recordingSearch", scheduledFixture())
        .answer("gridRowSearch", Fixtures.response("gridRowList"))
        .answer("gridRowSearch", endOfLineup());
    Intent intent =
        new Intent(RuntimeEnvironment.getApplication(), Guide.class);
    intent.putExtra(Guide.EXTRA_START_TIME, captureStart());
    mController = Robolectric.buildActivity(Guide.class, intent).setup();
    return mController.get();
  }

  /**
   * Give the screen a size and lay it out.
   *
   * Nothing does this on its own in a unit test, and until it happens a
   * RecyclerView has bound no rows at all -- so anything reading a row view
   * has to ask for it first.
   */
  private static void layOut(Guide activity) {
    View root = activity.findViewById(android.R.id.content);
    root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
    root.layout(0, 0, 1080, 1920);
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
    JsonNode body = Utils.parseJson(Utils.stringifyToJson(
        mTivo.firstOfType("gridRowSearch").getDataMap()));
    assertFalse("the first page starts at the top of the lineup",
        body.has("anchorChannelIdentifier"));
    // Without this the box returns channels the tuner cannot receive.
    assertEquals("true", body.path("isReceived").asText());
    assertEquals(GridRowSearch.PAGE_SIZE, body.path("count").asInt());
  }

  @Test
  public void theWindowIsSentAsUtcWithNoZoneMarker() {
    start();
    JsonNode body = Utils.parseJson(Utils.stringifyToJson(
        mTivo.firstOfType("gridRowSearch").getDataMap()));
    String from = body.path("minEndTime").asText();
    String to = body.path("maxStartTime").asText();
    assertTrue("minEndTime was " + from,
        from.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
    assertNotNull(Utils.parseDateTimeStr(from));
    // The span the screen opens on, which is what makes the grid scrollable
    // rather than a single screenful.
    long hours = (Utils.parseDateTimeStr(to).getTime()
        - Utils.parseDateTimeStr(from).getTime()) / 3600000L;
    assertEquals(6, hours);
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
}
