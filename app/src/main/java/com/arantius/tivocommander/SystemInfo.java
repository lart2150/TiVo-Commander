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

import java.util.ArrayList;
import java.util.List;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.arantius.tivocommander.rpc.MindRpc;
import com.arantius.tivocommander.rpc.request.BodyConfigSearch;
import com.arantius.tivocommander.rpc.request.PhoneHomeRequest;
import com.arantius.tivocommander.rpc.request.PhoneHomeStatus;
import com.arantius.tivocommander.rpc.request.SystemInformationGet;
import com.arantius.tivocommander.rpc.response.MindRpcResponse;
import com.arantius.tivocommander.rpc.response.MindRpcResponseListener;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * What the box has to say about itself, and a button to make it call home.
 *
 * Most of this is read-only reporting -- software version, disk, tuners,
 * network, temperature.  The part worth the screen is the service connection:
 * when the box last reached TiVo, whether that worked, and when it will try
 * again; plus a Network Connect that starts one now and follows it through,
 * which is otherwise something you can only watch on the television.
 */
public class SystemInfo extends BaseActivity {
  private JsonNode mSystemInfo;
  private JsonNode mBodyConfig;

  /** Set while a connection is being followed, so its log owns the panel. */
  private boolean mConnecting = false;
  /**
   * Bumped whenever something else takes the panel over, so a poll still in
   * flight from an earlier run knows to stop drawing into it.
   */
  private int mConnectRun = 0;
  private long mConnectStarted = 0;
  private String mLastStatus = null;
  private final List<String> mConnectSteps = new ArrayList<String>();
  /**
   * How the last connection ended, kept after it has.
   *
   * The report is re-read the moment a connection finishes -- its "last
   * attempt" line is exactly what just changed -- and that redraw used to
   * wipe the outcome off the screen about a second after it appeared.  So the
   * log stays above the refreshed report until something else is asked for.
   */
  private String mConnectResult = null;
  private final Handler mHandler = new Handler(Looper.getMainLooper());

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    if (MindRpc.init(this, getIntent().getExtras())) {
      return;
    }

    setContent(R.layout.system_info);
    setTitle(R.string.system_info);

    findViewById(R.id.system_info_connect).setOnClickListener(
        new View.OnClickListener() {
          public void onClick(View view) {
            startNetworkConnect();
          }
        });
    load();
  }

  /* ---- the report ---- */

  private void load() {
    load(true);
  }

  /**
   * @param forget whether to drop a finished connection's log.  A refresh
   *     the user asked for does; the automatic re-read at the end of a
   *     connection does not, since that log is the thing worth reading.
   */
  private void load(boolean forget) {
    mConnecting = false;
    mConnectRun++;
    if (forget) {
      mConnectSteps.clear();
      mConnectResult = null;
    }

    // A token per request, cleared by that request's own answer.  Sharing one
    // meant the first response hid the bar on behalf of the second, and an
    // error -- which leaves its field null -- left it up for good.
    final Object infoToken = new Object();
    final Object configToken = new Object();
    Utils.showProgress(this, infoToken, true);
    Utils.showProgress(this, configToken, true);

    MindRpc.addRequest(new SystemInformationGet(),
        new MindRpcResponseListener() {
          public void onResponse(MindRpcResponse response) {
            Utils.showProgress(SystemInfo.this, infoToken, false);
            if (Utils.isError(response)) {
              // Storing the error body would render a report of empty
              // sections; leaving it null just leaves those sections out.
              Utils.log("SystemInfo: system information failed: "
                  + Utils.errorText(response));
              return;
            }
            mSystemInfo = response.getBody();
            render();
          }
        });
    // Carries networkInterface and timeZoneName now that every request is
    // sent at schema 17; at 7 it had neither.
    MindRpc.addRequest(new BodyConfigSearch(),
        new MindRpcResponseListener() {
          public void onResponse(MindRpcResponse response) {
            Utils.showProgress(SystemInfo.this, configToken, false);
            if (Utils.isError(response)) {
              Utils.log("SystemInfo: body config failed: "
                  + Utils.errorText(response));
              return;
            }
            mBodyConfig = response.getBody().path("bodyConfig").path(0);
            render();
          }
        });
  }

  /**
   * Draw whatever has arrived so far.
   *
   * Both requests are in flight at once and either may answer first, so this
   * runs on each and simply leaves out the sections it has no data for yet.
   */
  private void render() {
    if (mConnecting) {
      // A connection is using the panel; its log is not to be painted over.
      return;
    }

    LinearLayout content = findViewById(R.id.system_info_content);
    content.removeAllViews();

    if (mConnectResult != null) {
      addConnectLog(content, mConnectResult);
    }

    if (mSystemInfo != null) {
      JsonNode service = mSystemInfo.path("serviceConnectionInfo");
      int mark = heading(content, R.string.system_info_service);
      row(content, R.string.system_info_last_attempt,
          when(service.path("lastAttemptTime").asText()));
      row(content, R.string.system_info_last_result,
          NetworkConnectWatch.humanize(
              service.path("lastCallStatus").asText()));
      row(content, R.string.system_info_last_success,
          when(service.path("lastSuccessfulTime").asText()));
      row(content, R.string.system_info_next_attempt,
          when(service.path("nextScheduledTime").asText()));
      row(content, R.string.system_info_guide_through,
          mSystemInfo.path("programInfoTo").asText());
      dropEmptySection(content, mark);

      mark = heading(content, R.string.system_info_recorder);
      row(content, R.string.system_info_service_state,
          NetworkConnectWatch.humanize(
              mSystemInfo.path("serviceState").asText()));
      row(content, R.string.system_info_software,
          mSystemInfo.path("softwareVersion").asText());
      row(content, R.string.system_info_hd_capacity, hours(
          mSystemInfo.path("recordingCapacityHdHours"),
          mSystemInfo.path("freeDiskSpaceHdHours")));
      row(content, R.string.system_info_temperature,
          mSystemInfo.path("internalTemperature").asText());
      row(content, R.string.system_info_remote_battery,
          mSystemInfo.path("remoteBatteryLevel").asText());
      dropEmptySection(content, mark);
    }

    if (mBodyConfig != null) {
      int mark = heading(content, R.string.system_info_disk);
      row(content, R.string.system_info_disk_used, diskUsed());
      dropEmptySection(content, mark);

      mark = heading(content, R.string.system_info_network);
      JsonNode interfaces = mBodyConfig.path("networkInterface");
      for (int i = 0; i < interfaces.size(); i++) {
        JsonNode iface = interfaces.path(i);
        // The one actually carrying traffic is the useful one, but both are
        // listed so "wireless: disconnected" is visible too.
        String value = NetworkConnectWatch.humanize(
            iface.path("interfaceState").asText());
        String address = iface.path("ipAddress").asText();
        if (!"".equals(address)) {
          value = value + "  " + address;
        }
        row(content, NetworkConnectWatch.humanize(
            iface.path("interfaceType").asText()), value);
      }
      row(content, getString(R.string.system_info_timezone),
          mBodyConfig.path("timeZoneName").asText());
      dropEmptySection(content, mark);
    }
  }

  /**
   * Take a heading back out again when nothing came to sit under it.
   *
   * Every row is dropped when the box did not send that field, so a whole
   * section can come to nothing -- an older schema answers with no network
   * interfaces at all -- and a heading standing alone over blank space reads
   * as a screen that failed to load.
   */
  private static void dropEmptySection(LinearLayout content, int headingAt) {
    if (content.getChildCount() == headingAt + 1) {
      content.removeViewAt(headingAt);
    }
  }

  /** @return where the heading landed, for {@link #dropEmptySection}. */
  private int heading(LinearLayout parent, int titleId) {
    TextView view = (TextView) getLayoutInflater()
        .inflate(R.layout.item_info_heading, parent, false);
    view.setText(titleId);
    int at = parent.getChildCount();
    parent.addView(view);
    return at;
  }

  private void row(LinearLayout parent, int labelId, String value) {
    row(parent, getString(labelId), value);
  }

  private void row(LinearLayout parent, String label, String value) {
    if (value == null || "".equals(value)) {
      // A field the box did not send is left out rather than shown blank.
      return;
    }
    View view =
        getLayoutInflater().inflate(R.layout.item_info_row, parent, false);
    ((TextView) view.findViewById(R.id.info_label)).setText(label);
    ((TextView) view.findViewById(R.id.info_value)).setText(value);
    parent.addView(view);
  }

  /** An RPC timestamp shown in the phone's own zone, or "" if absent. */
  private static String when(String raw) {
    return Utils.formatLocalDateTime(Utils.parseDateTimeStr(raw));
  }

  private String hours(JsonNode capacity, JsonNode free) {
    if (capacity.isMissingNode() || free.isMissingNode()) {
      return "";
    }
    return getString(R.string.system_info_hours, free.asInt(),
        capacity.asInt());
  }

  private String diskUsed() {
    // Kilobyte counts, as strings too large for an int.
    long used = mBodyConfig.path("userDiskUsed").asLong();
    long size = mBodyConfig.path("userDiskSize").asLong();
    if (size <= 0) {
      return "";
    }
    return getString(R.string.system_info_percent,
        (int) (100L * used / size));
  }

  /* ---- network connect ---- */

  /**
   * Start a service connection and follow it to the end.
   *
   * The log replaces the report while it runs, because the report's own
   * "last connected" lines are exactly what this is in the middle of changing.
   */
  private void startNetworkConnect() {
    mConnecting = true;
    mConnectRun++;
    mConnectStarted = System.currentTimeMillis();
    mLastStatus = null;
    mConnectSteps.clear();
    mConnectResult = null;
    drawConnect(getString(R.string.system_info_connect_starting));

    final int run = mConnectRun;
    MindRpc.addRequest(new PhoneHomeRequest(), new MindRpcResponseListener() {
      public void onResponse(MindRpcResponse response) {
        if (run != mConnectRun) {
          return;
        }
        if (Utils.isError(response)) {
          // Nothing was started, so there is nothing to follow.  Polling
          // anyway would report the *previous* connection's phases as this
          // one's.  (A box in "Pending Restart" refuses exactly here.)
          mConnectResult = getString(R.string.system_info_connect_refused,
              reasonFrom(response));
          drawConnect(mConnectResult);
          mConnecting = false;
          return;
        }
        pollConnect(run);
      }
    });
  }

  /**
   * What the box said about a refusal, as it said it.
   *
   * Deliberately not humanize(): that reads a camel case token like
   * "preparingToCallOverNetwork" and is wrong for prose.  It lowercases
   * everything and splits at every lower-to-upper boundary, so a message that
   * is already a sentence comes back mangled -- "TiVo is in Pending Restart"
   * reads out as "Ti vo is in pending restart".  The text field is the box's
   * own words and wants passing through untouched.
   */
  private String reasonFrom(MindRpcResponse response) {
    String text = Utils.errorText(response);
    return "".equals(text) ? getString(R.string.system_info_connect_no_reason)
        : text;
  }

  private void pollConnect(final int run) {
    if (run != mConnectRun) {
      return;
    }
    MindRpc.addRequest(new PhoneHomeStatus(), new MindRpcResponseListener() {
      public void onResponse(MindRpcResponse response) {
        if (run != mConnectRun) {
          return;
        }
        long elapsed = System.currentTimeMillis() - mConnectStarted;
        if (Utils.isError(response)) {
          // An error has no "phase", so reading it as a status would report
          // the box's refusal as the connection having "stopped: Unknown",
          // throwing away the one thing that explains it.
          mConnectResult = getString(R.string.system_info_connect_lost,
              NetworkConnectWatch.clock(elapsed), reasonFrom(response));
          drawConnect(mConnectResult);
          mConnecting = false;
          return;
        }
        JsonNode body = response.getBody();
        String phase = body.path("phase").asText();
        String status = body.path("status").asText();
        if ("".equals(phase)) {
          phase = "unknown";
        }
        if ("".equals(status)) {
          status = phase;
        }

        if (!status.equals(mLastStatus)
            && !NetworkConnectWatch.isStale(phase, elapsed)) {
          mLastStatus = status;
          mConnectSteps.add(NetworkConnectWatch.clock(elapsed) + "  "
              + NetworkConnectWatch.humanize(status));
        }

        if (NetworkConnectWatch.isFinished(phase, elapsed)) {
          mConnectResult = NetworkConnectWatch.SUCCEEDED.equals(phase)
              ? getString(R.string.system_info_connect_done,
                  NetworkConnectWatch.clock(elapsed))
              : getString(R.string.system_info_connect_stopped,
                  NetworkConnectWatch.clock(elapsed),
                  NetworkConnectWatch.humanize(status));
          drawConnect(mConnectResult);
          // The report's connection times are exactly what just changed, so
          // re-read them -- keeping the log, which render() draws above.
          mConnecting = false;
          load(false);
          return;
        }
        if (NetworkConnectWatch.isExpired(elapsed)) {
          mConnectResult = getString(R.string.system_info_connect_gave_up,
              NetworkConnectWatch.clock(elapsed));
          drawConnect(mConnectResult);
          mConnecting = false;
          return;
        }

        drawConnect(getString(R.string.system_info_connect_working,
            NetworkConnectWatch.clock(elapsed)));
        mHandler.postDelayed(new Runnable() {
          public void run() {
            pollConnect(run);
          }
        }, NetworkConnectWatch.pollDelayMs(elapsed));
      }
    });
  }

  private void drawConnect(String note) {
    LinearLayout content = findViewById(R.id.system_info_content);
    content.removeAllViews();
    addConnectLog(content, note);
  }

  private void addConnectLog(LinearLayout content, String note) {
    TextView view = (TextView) getLayoutInflater()
        .inflate(R.layout.item_info_log, content, false);
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < mConnectSteps.size(); i++) {
      text.append(mConnectSteps.get(i)).append("\n");
    }
    if (note != null) {
      text.append("\n").append(note);
    }
    view.setText(text.toString());
    content.addView(view);
  }

  /* ---- lifecycle ---- */

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    // Ahead of the common items, and in the bar rather than the overflow:
    // temperature and disk move while the screen is open, so re-reading is
    // the one thing you are likely to want twice.
    menu.add(Menu.NONE, R.id.menu_item_refresh, Menu.NONE, R.string.refresh)
        .setIcon(R.drawable.ic_refresh)
        .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
    Utils.createFullOptionsMenu(menu, this);
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    if (item.getItemId() == R.id.menu_item_refresh) {
      load();
      return true;
    }
    return super.onOptionsItemSelected(item);
  }

  @Override
  protected void onPause() {
    super.onPause();
    Utils.log("Activity:Pause:SystemInfo");
    // Stop polling while away; the box carries on connecting regardless.
    mConnectRun++;
    mHandler.removeCallbacksAndMessages(null);
    if (mConnecting) {
      // Release the panel as well.  Leaving this set made every later
      // render() return early, so the screen came back stuck on a frozen log
      // with no report under it and no way back but Refresh.
      mConnecting = false;
      mConnectResult = getString(R.string.system_info_connect_stopped_watching);
      // And redraw now: clearing the flag on its own changed nothing on
      // screen, so what came back was still the frozen "Working..." log with
      // the report missing underneath it.
      render();
    }
  }

  @Override
  protected void onResume() {
    super.onResume();
    Utils.log("Activity:Resume:SystemInfo");
    MindRpc.init(this, getIntent().getExtras());
  }
}
