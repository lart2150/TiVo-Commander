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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.arantius.tivocommander.rpc.MindRpc;
import com.arantius.tivocommander.rpc.request.CancelledSearch;
import com.arantius.tivocommander.rpc.response.MindRpcResponse;
import com.arantius.tivocommander.rpc.response.MindRpcResponseListener;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * What the box is not going to record, and why.
 *
 * The To Do list answers "what will record"; this is the other half, and the
 * useful part is the box's own reason -- a conflict with two other programs,
 * or someone having cancelled it -- which is otherwise invisible from the app.
 * Rows are grouped under that reason, and a long press offers to record one
 * after all, which goes through the same screen (and the same conflict
 * warning) as recording from anywhere else.
 */
public class WontRecord extends BaseActivity {
  /**
   * How far back to keep showing things.
   *
   * The box remembers cancellations long after the program aired, and a list
   * of months of them buries the ones still worth acting on.  A day back is
   * enough to answer "why did last night's episode not record?" without that.
   */
  private static final long RECENT_PAST_MS = 24 * 60 * 60 * 1000L;
  /**
   * Most pages to read before giving up.
   *
   * recordingSearch is asked by count and offset with no order pinned, so a
   * result set that shifts underneath could keep answering full pages for
   * ever.  Far more than any real cancelled list.
   */
  private static final int MAX_PAGES = 40;

  /** A reason heading, or one program under it. */
  private static class Item {
    final String heading;
    final JsonNode recording;

    Item(String heading, JsonNode recording) {
      this.heading = heading;
      this.recording = recording;
    }

    boolean isHeading() {
      return recording == null;
    }
  }

  private static final int TYPE_HEADING = 0;
  private static final int TYPE_ROW = 1;

  private final List<JsonNode> mRecordings = new ArrayList<JsonNode>();
  private final List<Item> mItems = new ArrayList<Item>();
  private ItemAdapter mAdapter;

  /** SubscribeOffer can turn a row here back into a scheduled recording. */
  private final ActivityResultLauncher<Intent> mRefreshLauncher =
      registerForActivityResult(
          new ActivityResultContracts.StartActivityForResult(),
          new ActivityResultCallback<ActivityResult>() {
            public void onActivityResult(ActivityResult result) {
              // Whatever was just scheduled is no longer cancelled, so the
              // whole list is re-read rather than guessing which row moved.
              mRecordings.clear();
              requestPage(0);
            }
          });

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    if (MindRpc.init(this, getIntent().getExtras())) {
      return;
    }

    setContent(R.layout.wont_record);
    setTitle(R.string.wont_record);

    mAdapter = new ItemAdapter();
    RecyclerView list = findViewById(R.id.wont_record_list);
    list.setLayoutManager(new LinearLayoutManager(this));
    list.setAdapter(mAdapter);

    requestPage(0);
  }

  /* ---- loading ---- */

  private void requestPage(final int offset) {
    final Object token = new Object();
    Utils.showProgress(this, token, true);
    MindRpc.addRequest(new CancelledSearch(offset),
        new MindRpcResponseListener() {
          public void onResponse(MindRpcResponse response) {
            Utils.showProgress(WontRecord.this, token, false);
            if (Utils.isError(response)) {
              // An error body has no "recording" field, so falling through
              // would count it as an empty page and tell the user everything
              // is going to record.
              Utils.toast(WontRecord.this,
                  getString(R.string.wont_record_failed), Toast.LENGTH_SHORT);
              return;
            }
            JsonNode recordings = response.getBody().path("recording");
            for (int i = 0; i < recordings.size(); i++) {
              mRecordings.add(recordings.path(i));
            }
            int next = offset + CancelledSearch.PAGE_SIZE;
            if (recordings.size() >= CancelledSearch.PAGE_SIZE
                && next < MAX_PAGES * CancelledSearch.PAGE_SIZE) {
              requestPage(next);
            } else {
              rebuild();
            }
          }
        });
  }

  /**
   * Drop what is too old to act on, group by reason, and order by air time.
   *
   * The whole list is replaced here -- rows, headings and count all change
   * together as pages arrive -- so this is the case notifyDataSetChanged is
   * actually for, rather than a stand-in for working out what moved.
   */
  @SuppressLint("NotifyDataSetChanged")
  private void rebuild() {
    long oldest = System.currentTimeMillis() - RECENT_PAST_MS;

    List<JsonNode> kept = new ArrayList<JsonNode>();
    for (int i = 0; i < mRecordings.size(); i++) {
      JsonNode recording = mRecordings.get(i);
      Date start = startOf(recording);
      if (start != null && start.getTime() >= oldest) {
        kept.add(recording);
      }
    }
    // Sorted on values worked out once each.  The comparator used to call
    // startOf() on both operands per comparison, and every one of those built
    // a SimpleDateFormat and re-parsed the timestamp -- thousands of them for
    // a list of a couple of hundred, on the main thread.
    final Map<JsonNode, String> labels = new HashMap<JsonNode, String>();
    final Map<JsonNode, Long> starts = new HashMap<JsonNode, Long>();
    for (int i = 0; i < kept.size(); i++) {
      JsonNode recording = kept.get(i);
      labels.put(recording, describeReason(reasonOf(recording)));
      Date start = startOf(recording);
      starts.put(recording, start == null ? 0L : start.getTime());
    }
    Collections.sort(kept, new Comparator<JsonNode>() {
      public int compare(JsonNode a, JsonNode b) {
        // By label, so rows that will share a heading sort together whatever
        // raw reason they arrived with.
        int byLabel = labels.get(a).compareTo(labels.get(b));
        if (byLabel != 0) {
          return byLabel;
        }
        return starts.get(a).compareTo(starts.get(b));
      }
    });

    mItems.clear();
    String heading = null;
    for (int i = 0; i < kept.size(); i++) {
      JsonNode recording = kept.get(i);
      // Grouped on the label, not the raw reason.  Several raw reasons map to
      // one label -- "cancelled" and "explicitlyDeleted" are both Cancelled --
      // and grouping on the raw string split those across two identical
      // headings.
      String label = describeReason(reasonOf(recording));
      if (!label.equals(heading)) {
        heading = label;
        mItems.add(new Item(label, null));
      }
      mItems.add(new Item(null, recording));
    }

    mAdapter.notifyDataSetChanged();
    TextView empty = findViewById(R.id.wont_record_empty);
    empty.setVisibility(mItems.isEmpty() ? View.VISIBLE : View.GONE);
  }

  private static String reasonOf(JsonNode recording) {
    return recording.path("cancellationReason").asText();
  }

  private static Date startOf(JsonNode recording) {
    return Utils.parseDateTimeStr(recording.path("startTime").asText());
  }

  /**
   * Turn the box's reason into something readable.
   *
   * The reasons named here are the ones a real Bolt has actually sent --
   * "explicitlyDeletedFromToDo" among them, which kmttg's older capture does
   * not contain, so this vocabulary is not a closed set and should not be
   * treated as one.  Anything unrecognised is split on its camel case rather
   * than dropped or lumped under "unknown", so a reason that turns up later
   * still reads as words.
   */
  private String describeReason(String reason) {
    if ("programSourceConflict".equals(reason)) {
      return getString(R.string.wont_record_conflict);
    }
    if ("explicitlyDeletedFromToDo".equals(reason)) {
      return getString(R.string.wont_record_removed_from_todo);
    }
    if ("explicitlyDeleted".equals(reason) || "cancelled".equals(reason)) {
      return getString(R.string.wont_record_cancelled_by_you);
    }
    if ("".equals(reason)) {
      return getString(R.string.wont_record_other);
    }
    // The same job NetworkConnectWatch does for the box's connection statuses,
    // so it is done in one place: two copies of this regex drifted apart on
    // casing, and the app rendered TiVo's camel case two ways on two screens.
    return NetworkConnectWatch.humanize(reason);
  }

  private static String formatWhen(JsonNode recording) {
    return Utils.formatLocalDateTime(startOf(recording));
  }

  /* ---- rows ---- */

  private class ItemHolder extends RecyclerView.ViewHolder {
    /** Null on a heading, whose whole view is the text. */
    final TextView title;
    final TextView details;

    ItemHolder(View view) {
      super(view);
      // Looked up once here rather than on every bind, which is the whole
      // point of a holder.
      title = view.findViewById(R.id.wont_record_title);
      details = view.findViewById(R.id.wont_record_details);
    }
  }

  private class ItemAdapter extends RecyclerView.Adapter<ItemHolder> {
    @Override
    public int getItemViewType(int position) {
      return mItems.get(position).isHeading() ? TYPE_HEADING : TYPE_ROW;
    }

    @NonNull
    @Override
    public ItemHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
      int layout = type == TYPE_HEADING
          ? R.layout.item_wont_record_heading
          : R.layout.item_wont_record;
      return new ItemHolder(LayoutInflater.from(parent.getContext())
          .inflate(layout, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ItemHolder holder, int position) {
      Item item = mItems.get(position);
      if (item.isHeading()) {
        ((TextView) holder.itemView).setText(item.heading);
        return;
      }

      final JsonNode recording = item.recording;
      TextView title = holder.title;
      TextView details = holder.details;

      title.setText(recording.path("title").asText());

      String subtitle = recording.path("subtitle").asText();
      String channel = recording.path("channel").path("channelNumber").asText()
          + " " + recording.path("channel").path("callSign").asText();
      String when = formatWhen(recording);
      details.setText("".equals(subtitle)
          ? getString(R.string.wont_record_details, when, channel)
          : getString(R.string.wont_record_details_episode, subtitle, when,
              channel));

      holder.itemView.setOnClickListener(new View.OnClickListener() {
        public void onClick(View view) {
          Intent intent = new Intent(WontRecord.this, ExploreTabs.class);
          intent.putExtra("contentId", recording.path("contentId").asText());
          intent.putExtra("collectionId",
              recording.path("collectionId").asText());
          intent.putExtra("offerId", recording.path("offerId").asText());
          mRefreshLauncher.launch(intent);
        }
      });
      holder.itemView.setOnLongClickListener(new View.OnLongClickListener() {
        public boolean onLongClick(View view) {
          promptRecord(recording);
          return true;
        }
      });
    }

    @Override
    public int getItemCount() {
      return mItems.size();
    }
  }

  /* ---- recording one after all ---- */

  private void promptRecord(final JsonNode recording) {
    final ArrayList<String> choices = new ArrayList<String>();
    choices.add(getString(R.string.record));

    ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
        android.R.layout.select_dialog_item, choices);
    DialogInterface.OnClickListener onClick =
        new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int which) {
            // Straight to the normal record screen: if this was cancelled for
            // a conflict, that screen is where the conflict is spelled out and
            // where the choice to record anyway belongs.
            Intent intent = new Intent(getBaseContext(), SubscribeOffer.class);
            intent.putExtra("offerId", recording.path("offerId").asText());
            intent.putExtra("contentId", recording.path("contentId").asText());
            mRefreshLauncher.launch(intent);
          }
        };

    AlertDialog.Builder builder = new AlertDialog.Builder(this);
    builder.setTitle(recording.path("title").asText());
    builder.setAdapter(adapter, onClick);
    builder.create().show();
  }

  /* ---- lifecycle ---- */

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    Utils.createFullOptionsMenu(menu, this);
    return true;
  }

  @Override
  protected void onPause() {
    super.onPause();
    Utils.log("Activity:Pause:WontRecord");
  }

  @Override
  protected void onResume() {
    super.onResume();
    Utils.log("Activity:Resume:WontRecord");
    MindRpc.init(this, getIntent().getExtras());
  }
}
