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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
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
import com.fasterxml.jackson.databind.JsonNode;

/** The System Info screen, driven against recorded responses. */
@RunWith(RobolectricTestRunner.class)
public class SystemInfoTest {
  private ActivityController<SystemInfo> mController;
  private FakeTivo mTivo;

  @After
  public void tearDown() {
    if (mController != null) {
      mController.close();
    }
    FakeTivo.uninstall();
  }

  /** The captured bodyConfig that carries the network interfaces. */
  private static JsonNode networkBodyConfig() {
    for (JsonNode response : Fixtures.responses("bodyConfigList")) {
      if (response.path("bodyConfig").path(0).has("networkInterface")) {
        return response;
      }
    }
    throw new AssertionError("no captured bodyConfigList with interfaces");
  }

  private SystemInfo start() {
    mTivo = FakeTivo.install()
        .answer("systemInformationGet", Fixtures.response("systemInformation"))
        .answer("bodyConfigSearch", networkBodyConfig())
        // phoneHomeRequest only acknowledges; the phase comes from the status
        // call it sets off, which here reports the first phase of a real one.
        .answer("phoneHomeRequest", Fixtures.response("success"))
        .answer("phoneHomeStatusEventRegister",
            Fixtures.response("phoneHomeStatusEvent"));
    mController = Robolectric.buildActivity(SystemInfo.class).setup();
    SystemInfo activity = mController.get();
    mTivo.deliver();
    return activity;
  }

  private static List<String> lines(SystemInfo activity) {
    List<String> out = new ArrayList<String>();
    collect(activity.findViewById(R.id.system_info_content), out);
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

  private static boolean has(List<String> lines, String needle) {
    for (String line : lines) {
      if (line.contains(needle)) {
        return true;
      }
    }
    return false;
  }

  @Test
  public void itAsksTheBoxAboutItselfAndItsConfiguration() {
    start();
    assertTrue("should ask for system information: " + mTivo.sentTypes(),
        mTivo.sentTypes().contains("systemInformationGet"));
    assertTrue("should ask for the body config: " + mTivo.sentTypes(),
        mTivo.sentTypes().contains("bodyConfigSearch"));
  }

  @Test
  public void itReportsWhenTheBoxLastReachedTheService() {
    SystemInfo activity = start();
    List<String> shown = lines(activity);
    assertTrue("missing the service section: " + shown,
        shown.contains(activity.getString(R.string.system_info_service)));
    assertTrue("missing the last attempt: " + shown,
        shown.contains(activity.getString(R.string.system_info_last_attempt)));
    assertTrue("missing when it will try next: " + shown,
        shown.contains(activity.getString(R.string.system_info_next_attempt)));
    // The captured connection succeeded, humanised from "succeeded".
    assertTrue("missing the result of the last call: " + shown,
        has(shown, "Succeeded"));
  }

  @Test
  public void itShowsBothNetworkInterfacesIncludingTheDisconnectedOne() {
    SystemInfo activity = start();
    List<String> shown = lines(activity);
    assertTrue("missing the wired interface: " + shown, has(shown, "Wired"));
    assertTrue("missing the wireless interface: " + shown,
        has(shown, "Wireless"));
    assertTrue("the connected one should show its address: " + shown,
        has(shown, "192.168.0.2"));
    assertTrue("the idle one should say so: " + shown,
        has(shown, "Disconnected"));
  }

  @Test
  public void fieldsTheBoxDidNotSendAreLeftOutRatherThanShownBlank() {
    SystemInfo activity = start();
    for (String line : lines(activity)) {
      assertFalse("a blank value was rendered", line.trim().isEmpty());
    }
  }

  @Test
  public void aSectionWithNothingInItLosesItsHeadingToo() {
    // The older schema answers with no interfaces at all.  A heading standing
    // over blank space reads as a screen that failed to load, so it goes.
    JsonNode bare = networkBodyConfig().deepCopy();
    com.fasterxml.jackson.databind.node.ObjectNode config =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            bare.path("bodyConfig").get(0);
    config.remove("networkInterface");
    config.remove("timeZoneName");

    mTivo = FakeTivo.install()
        .answer("systemInformationGet", Fixtures.response("systemInformation"))
        .answer("bodyConfigSearch", bare);
    mController = Robolectric.buildActivity(SystemInfo.class).setup();
    SystemInfo activity = mController.get();
    mTivo.deliver();

    List<String> shown = lines(activity);
    assertFalse("the empty network section should be gone: " + shown,
        shown.contains(activity.getString(R.string.system_info_network)));
    // The sections that do have content are untouched.
    assertTrue("the disk section should remain: " + shown,
        shown.contains(activity.getString(R.string.system_info_disk)));
  }

  @Test
  public void theNetworkConnectButtonStartsAConnectionAndWatchesIt() {
    SystemInfo activity = start();
    mTivo.clearSent();

    activity.findViewById(R.id.system_info_connect).performClick();
    mTivo.deliver();

    assertTrue("should tell the box to call home: " + mTivo.sentTypes(),
        mTivo.sentTypes().contains("phoneHomeRequest"));
    assertTrue("should then follow the connection: " + mTivo.sentTypes(),
        mTivo.sentTypes().contains("phoneHomeStatusEventRegister"));
  }
}
