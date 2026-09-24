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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import com.arantius.tivocommander.rpc.MindRpc;
import com.arantius.tivocommander.rpc.request.KeyEventSend;
import com.fasterxml.jackson.databind.JsonNode;

/** The remote's button-to-event lookup. */
@RunWith(RobolectricTestRunner.class)
public class RemoteTest {
  @Before
  public void setUpDevice() {
    Device device = new Device();
    device.tsn = Fixtures.TSN;
    MindRpc.mTivoDevice = device;
  }

  private static JsonNode dataOf(KeyEventSend request) {
    assertNotNull(request);
    return Utils.parseJson(Utils.stringifyToJson(request.getDataMap()));
  }

  private static void assertSendsEvent(int viewId, String event) {
    assertEquals(event, dataOf(Remote.viewIdToEvent(viewId)).path("event")
        .asText());
  }

  /** Every R.id named remote_*, which is every button the remote can show. */
  private static List<Field> remoteButtonIds() throws Exception {
    List<Field> out = new ArrayList<Field>();
    for (Field field : R.id.class.getFields()) {
      if (field.getName().startsWith("remote_")) {
        out.add(field);
      }
    }
    return out;
  }

  @Test
  public void everyRemoteButtonHasAnEvent() throws Exception {
    // Remote.onClick() hands the result straight to MindRpc.addRequest(),
    // which puts it on a queue that rejects null -- so a button with no entry
    // in the lookup crashes the app when it is pressed.
    List<Field> ids = remoteButtonIds();
    assertTrue("should have found the remote's buttons", ids.size() > 30);
    for (Field field : ids) {
      int id = field.getInt(null);
      assertNotNull("no event for R.id." + field.getName(),
          Remote.viewIdToEvent(id));
    }
  }

  @Test
  public void namedButtonsSendTheirOwnEvent() {
    assertSendsEvent(R.id.remote_tivo, "tivo");
    assertSendsEvent(R.id.remote_liveTv, "liveTv");
    assertSendsEvent(R.id.remote_select, "select");
    assertSendsEvent(R.id.remote_reverse, "reverse");
    assertSendsEvent(R.id.remote_forward, "forward");
    assertSendsEvent(R.id.remote_thumbsUp, "thumbsUp");
    assertSendsEvent(R.id.remote_thumbsDown, "thumbsDown");
    assertSendsEvent(R.id.remote_actionA, "actionA");
    assertSendsEvent(R.id.remote_clear, "clear");
    assertSendsEvent(R.id.remote_enter, "enter");
  }

  @Test
  public void numberButtonsSendTheirDigitAsAscii() {
    JsonNode zero = dataOf(Remote.viewIdToEvent(R.id.remote_num0));
    assertEquals("ascii", zero.path("event").asText());
    assertEquals((int) '0', zero.path("value").asInt());

    JsonNode nine = dataOf(Remote.viewIdToEvent(R.id.remote_num9));
    assertEquals("ascii", nine.path("event").asText());
    assertEquals((int) '9', nine.path("value").asInt());
  }

  @Test
  public void everyButtonMapsToSomethingDifferent() {
    // Two buttons sharing an event would be a copy-paste slip in the table
    // and would silently send the wrong command.
    List<String> seen = new ArrayList<String>();
    for (int id : new int[] {R.id.remote_up, R.id.remote_down,
        R.id.remote_left, R.id.remote_right, R.id.remote_channelUp,
        R.id.remote_channelDown, R.id.remote_play, R.id.remote_pause,
        R.id.remote_reverse, R.id.remote_forward, R.id.remote_replay,
        R.id.remote_advance, R.id.remote_slow, R.id.remote_record}) {
      String event = dataOf(Remote.viewIdToEvent(id)).path("event").asText();
      assertTrue("duplicate event " + event, !seen.contains(event));
      seen.add(event);
    }
  }

  @Test
  public void anIdThatIsNotARemoteButtonHasNoEvent() {
    assertNull(Remote.viewIdToEvent(R.id.keyboard_activator));
    assertNull(Remote.viewIdToEvent(R.id.activity_content));
    assertNull(Remote.viewIdToEvent(0));
  }
}
