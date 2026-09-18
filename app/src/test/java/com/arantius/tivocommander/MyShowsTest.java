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

import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;

import com.arantius.tivocommander.rpc.FakeTivo;
import com.fasterxml.jackson.databind.JsonNode;

/** My Shows, driven against recorded responses. */
@RunWith(RobolectricTestRunner.class)
public class MyShowsTest {
  private ActivityController<MyShows> mController;
  private FakeTivo mTivo;

  @After
  public void tearDown() {
    if (mController != null) {
      mController.close();
    }
    FakeTivo.uninstall();
  }

  private MyShows start() {
    mTivo = FakeTivo.install()
        .answer("bodyConfigSearch", Fixtures.response("bodyConfigList"))
        .answer("recordingFolderItemSearch", Fixtures.response("idSequence"))
        .answer("recordingFolderItemSearch",
            Fixtures.response("recordingFolderItemList"));
    mController = Robolectric.buildActivity(MyShows.class).setup();
    return mController.get();
  }

  @Test
  public void itAsksForTheFolderListingAndTheDiskConfig() {
    start();
    assertTrue("should list the folder: " + mTivo.sentTypes(),
        mTivo.sentTypes().contains("recordingFolderItemSearch"));
    assertTrue("should ask for the disk config: " + mTivo.sentTypes(),
        mTivo.sentTypes().contains("bodyConfigSearch"));
  }

  @Test
  public void theTopLevelListingAsksForNoParticularFolder() {
    start();
    JsonNode body = Utils.parseJson(Utils.stringifyToJson(
        mTivo.firstOfType("recordingFolderItemSearch").getDataMap()));
    assertFalse("the top level has no parent folder",
        body.has("parentRecordingFolderItemId"));
    assertEquals("idSequence", body.path("format").asText());
  }

  @Test
  public void theDiskMeterShowsThePercentageUsed() {
    MyShows activity = start();
    mTivo.deliver();

    TextView meterText = activity.findViewById(R.id.meter_text);
    ProgressBar meter = activity.findViewById(R.id.meter);
    assertNotNull(meterText);
    // 2522634240 of 5818371792 kilobytes, as the captured box reported.
    assertEquals("43% Disk Used", meterText.getText().toString());
    assertTrue("the bar should be filled to match",
        meter.getProgress() > 0 && meter.getProgress() < meter.getMax());
  }

  @Test
  public void theListGetsARowPerShowPlusTheDeletedFolder() {
    MyShows activity = start();
    mTivo.deliver();

    int ids = Fixtures.response("idSequence").path("objectIdAndType").size();
    ListView list = activity.getListView();
    // At the top level the screen adds its own "Recently Deleted" entry.
    assertEquals(ids + 1, list.getAdapter().getCount());
  }

  @Test
  public void anEmptyAnswerDoesNotLeaveTheScreenUp() {
    // The box answers an unknown folder with a body carrying no ids at all;
    // the screen is supposed to give up and go back rather than sit blank.
    mTivo = FakeTivo.install()
        .answer("bodyConfigSearch", Fixtures.response("bodyConfigList"))
        .answer("recordingFolderItemSearch", Utils.parseJson("{}"));
    mController = Robolectric.buildActivity(MyShows.class).setup();
    mTivo.deliver();

    assertTrue("should have finished", mController.get().isFinishing());
  }
}
