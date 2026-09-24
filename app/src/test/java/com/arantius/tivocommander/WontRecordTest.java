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

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;

import com.arantius.tivocommander.rpc.FakeTivo;
import com.arantius.tivocommander.rpc.request.CancelledSearch;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** The Won't Record list, driven against a recorded response. */
@RunWith(RobolectricTestRunner.class)
public class WontRecordTest {
  private ActivityController<WontRecord> mController;
  private FakeTivo mTivo;

  @After
  public void tearDown() {
    if (mController != null) {
      mController.close();
    }
    FakeTivo.uninstall();
  }

  /** The captured cancelled page: a recordingList carrying reasons. */
  private static JsonNode cancelledFixture() {
    for (JsonNode response : Fixtures.responses("recordingList")) {
      if (response.path("recording").path(0).has("cancellationReason")) {
        return response;
      }
    }
    throw new AssertionError("no captured recordingList of cancelled shape");
  }

  /**
   * The capture with its air times moved and two reasons present.
   *
   * The screen hides anything that aired more than a day ago, so a capture
   * taken months back would correctly show nothing at all and test none of the
   * grouping.  The fixture supplies the real shape of a cancelled recording;
   * the timing is set here because the timing is what is under test.
   */
  private static JsonNode reTimed() {
    JsonNode body = cancelledFixture().deepCopy();
    JsonNode recordings = body.path("recording");
    long hour = 60 * 60 * 1000L;
    long[] offsets = { 2 * hour, 26 * hour, -2 * hour, -48 * hour };
    String[] reasons = { "programSourceConflict", "programSourceConflict",
        "explicitlyDeleted", "explicitlyDeleted" };
    for (int i = 0; i < recordings.size(); i++) {
      ObjectNode recording = (ObjectNode) recordings.get(i);
      recording.put("startTime", Utils.formatDateTimeStr(
          new Date(System.currentTimeMillis() + offsets[i])));
      recording.put("cancellationReason", reasons[i]);
    }
    return body;
  }

  private WontRecord start(JsonNode answer) {
    mTivo = FakeTivo.install().answer("recordingSearch", answer);
    mController = Robolectric.buildActivity(WontRecord.class).setup();
    WontRecord activity = mController.get();
    mTivo.deliver();
    layOut(activity);
    return activity;
  }

  /** Nothing lays a RecyclerView out in a unit test; this does. */
  private static void layOut(WontRecord activity) {
    View root = activity.findViewById(android.R.id.content);
    root.measure(
        View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
    root.layout(0, 0, 1080, 1920);
  }

  /** Every line of text the list is currently showing, top to bottom. */
  private static List<String> lines(WontRecord activity) {
    List<String> out = new ArrayList<String>();
    collect(activity.findViewById(R.id.wont_record_list), out);
    return out;
  }

  private static void collect(View view, List<String> out) {
    if (view instanceof TextView) {
      out.add(((TextView) view).getText().toString());
      return;
    }
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        collect(group.getChildAt(i), out);
      }
    }
  }

  @Test
  public void itAsksForCancelledRecordings() {
    start(reTimed());
    JsonNode body = Utils.parseJson(Utils.stringifyToJson(
        mTivo.firstOfType("recordingSearch").getDataMap()));
    assertEquals("cancelled", body.path("state").path(0).asText());
    assertEquals(CancelledSearch.PAGE_SIZE, body.path("count").asInt());
    // The reason is the whole point of the screen, so it has to be asked for.
    String template = Utils.stringifyToJson(body.path("responseTemplate"));
    assertTrue("the response template should ask for cancellationReason",
        template.contains("cancellationReason"));
  }

  @Test
  public void rowsAreGroupedUnderTheReasonTheBoxGave() {
    WontRecord activity = start(reTimed());
    List<String> shown = lines(activity);

    int conflict = shown.indexOf(
        activity.getString(R.string.wont_record_conflict));
    int cancelled = shown.indexOf(
        activity.getString(R.string.wont_record_cancelled_by_you));
    assertTrue("conflict heading missing from " + shown, conflict >= 0);
    assertTrue("cancelled heading missing from " + shown, cancelled >= 0);
    assertTrue("each reason should head its own group",
        conflict != cancelled);
  }

  @Test
  public void thingsThatAiredLongAgoAreLeftOut() {
    WontRecord activity = start(reTimed());
    // Of the four in the capture, one aired two days ago; the screen keeps a
    // day of history so that one should be gone and the other three shown.
    assertEquals("only recent and upcoming rows belong here", 3,
        countRows(activity.findViewById(R.id.wont_record_list)));
  }

  /** How many program rows the list is showing, headings excluded. */
  private static int countRows(View view) {
    if (view.getId() == R.id.wont_record_title) {
      return 1;
    }
    int found = 0;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        found += countRows(group.getChildAt(i));
      }
    }
    return found;
  }

  @Test
  public void anEmptyListSaysSoRatherThanShowingNothing() {
    JsonNode body = cancelledFixture().deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) body)
        .putArray("recording");
    WontRecord activity = start(body);

    TextView empty = activity.findViewById(R.id.wont_record_empty);
    assertNotNull(empty);
    assertEquals(View.VISIBLE, empty.getVisibility());
  }

  @Test
  public void aRefusedRequestIsNotReportedAsNothingToShow() {
    // An error body has no "recording" field, so treating it as data would
    // tell the user everything is going to record when nothing was read.
    WontRecord activity = start(Utils.parseJson(
        "{\"type\": \"error\", \"code\": \"badRequest\","
            + " \"text\": \"no\"}"));

    TextView empty = activity.findViewById(R.id.wont_record_empty);
    assertNotNull(empty);
    assertEquals("an error must not claim the list is empty", View.GONE,
        empty.getVisibility());
  }

  @Test
  public void aReasonNeverSeenBeforeIsStillReadable() {
    JsonNode body = reTimed();
    ((ObjectNode) body.path("recording").get(0))
        .put("cancellationReason", "someBrandNewReason");
    WontRecord activity = start(body);
    // Not dropped, and split into words rather than left as camel case.  The
    // casing is NetworkConnectWatch.humanize's, which this screen now shares
    // with System Info; the heading is drawn in caps either way.
    assertTrue("unknown reasons should still be spelled out: " + lines(activity),
        lines(activity).contains("Some brand new reason"));
  }
}
