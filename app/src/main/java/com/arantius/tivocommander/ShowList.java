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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.AlertDialog.Builder;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;
import android.util.Pair;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.AdapterView.OnItemLongClickListener;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.arantius.tivocommander.rpc.MindRpc;
import com.arantius.tivocommander.rpc.request.MindRpcRequest;
import com.arantius.tivocommander.rpc.request.RecordingFolderItemSearch;
import com.arantius.tivocommander.rpc.request.RecordingSearch;
import com.arantius.tivocommander.rpc.request.RecordingUpdate;
import com.arantius.tivocommander.rpc.request.TodoRecordingSearch;
import com.arantius.tivocommander.rpc.request.UiNavigate;
import com.arantius.tivocommander.rpc.response.MindRpcResponse;
import com.arantius.tivocommander.rpc.response.MindRpcResponseListener;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

public abstract class ShowList extends ListActivityCompat implements
    OnItemLongClickListener, DialogInterface.OnClickListener {
  protected class ShowsAdapter extends ArrayAdapter<JsonNode> {
    protected Context mContext;

    public ShowsAdapter(Context context) {
      super(context, 0, mShowData);
      mContext = context;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
      View v = convertView;

      if (mShowStatus.get(position) == ShowStatus.MISSING) {
        // If the show for this position is missing, fetch it and more, if they
        // exist, up to a limit of MAX_SHOW_REQUEST_BATCH.
        ArrayList<JsonNode> showIds = new ArrayList<JsonNode>();
        ArrayList<Integer> slots = new ArrayList<Integer>();
        int i = position;
        while (i < mShowStatus.size()) {
          if (mShowStatus.get(i) == ShowStatus.MISSING) {
            JsonNode showId = mShowIds.get(i);
            if ("deleted".equals(showId.asText())) {
              mShowData.set(i, mDeletedItem);
              mShowStatus.set(i, ShowStatus.LOADED);
            } else {
              showIds.add(showId);
              slots.add(i);
              mShowStatus.set(i, ShowStatus.LOADING);
              if (showIds.size() >= MAX_SHOW_REQUEST_BATCH) {
                break;
              }
            }
          }
          i++;
        }

        // We could rarely have no shows, if the "recently deleted" item
        // which we don't fetch falls right on the border.
        if (showIds.size() > 0) {
          MindRpcRequest req;
          if (mContext instanceof ToDo) {
            req = new TodoRecordingSearch(showIds, mOrderBy);
          } else if (mContext instanceof MyShows) {
            if ("deleted".equals(mFolderId)) {
              req = new RecordingSearch(showIds);
            } else {
              req = new RecordingFolderItemSearch(showIds, mOrderBy);
            }
          } else {
            Utils.logError("Unsupported context!");
            return null;
          }
          mRequestSlotMap.put(req.getRpcId(), slots);
          MindRpc.addRequest(req, mDetailCallback);
          setProgressIndicator(1);
        }
      }

      LayoutInflater vi =
          (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);
      if (mShowStatus.get(position) == ShowStatus.LOADED) {
        // If this item is available, display it.
        v = vi.inflate(R.layout.item_my_shows, parent, false);
        final JsonNode item = mShowData.get(position);
        final JsonNode recording = getRecordingFromItem(item);

        ((TextView) v.findViewById(R.id.show_title)).setText(
            Utils.stripQuotes(item.path("title").asText()));

        Integer folderItemCount = item.path("folderItemCount").asInt();
        final TextView folderNum = (TextView) v.findViewById(R.id.folder_num);
        folderNum.setText(folderItemCount > 0 ? folderItemCount.toString() : "");
        // Spoken before the title, so on its own it is a bare number.
        folderNum.setContentDescription(folderItemCount > 0
            ? getResources().getQuantityString(
                R.plurals.a11y_episode_count, folderItemCount, folderItemCount)
            : null);

        String channelStr = "";
        JsonNode channel = recording.path("channel");
        if (folderItemCount == 0 && !channel.isMissingNode()) {
          channelStr =
              String.format("%s %s", channel.path("channelNumber")
                  .asText(), channel.path("callSign").asText());
        }
        ((TextView) v.findViewById(R.id.show_channel)).setText(channelStr);

        TextView episodeNumView = (TextView) v.findViewById(R.id.episode_num);
        episodeNumView.setVisibility(View.GONE);
        if (0 == folderItemCount && item != mDeletedItem) {
          if (recording.has("hdtv") && recording.path("hdtv").asBoolean()) {
            v.findViewById(R.id.badge_hd).setVisibility(View.VISIBLE);
          }
          if (recording.path("episodic").asBoolean()) {
            if (!recording.path("repeat").asBoolean()) {
              v.findViewById(R.id.badge_new).setVisibility(View.VISIBLE);
            }

            Integer seasonNum = recording.path("seasonNumber").asInt();
            Integer episodeNum = recording.path("episodeNum").path(0).asInt();
            if (seasonNum > 0 && episodeNum > 0) {
              episodeNumView.setVisibility(View.VISIBLE);
              episodeNumView.setText(String.format(
                  Locale.ENGLISH, "S%02dE%02d", seasonNum, episodeNum));
            }
          }
        }

        String startTimeStr = item.path("startTime").asText();
        if ("".equals(startTimeStr)) {
          // Rarely the time is only on the recording, not the item.
          startTimeStr = recording.path("startTime").asText();
        }

        if ("".equals(startTimeStr)
            || "1970".equals(startTimeStr.substring(0, 4))) {
          v.findViewById(R.id.show_time).setVisibility(View.GONE);
        } else {
          Date startTime = Utils.parseDateTimeStr(startTimeStr);
          String timeFormat = "EEE M/d";
          if (mContext instanceof ToDo) {
            timeFormat += "\nh:mm aa";
          }
          SimpleDateFormat dateFormatter =
              new SimpleDateFormat(timeFormat, Locale.US);
          String timeStr = dateFormatter.format(startTime);
          ((TextView) v.findViewById(R.id.show_time)).setText(timeStr);
        }

        final int iconId = getIconForItem(item);
        final ImageView icon = (ImageView) v.findViewById(R.id.show_icon);
        icon.setImageDrawable(ContextCompat.getDrawable(ShowList.this, iconId));
        // This icon is the only place a row says whether it is a folder, or
        // recording, or expired, so it has to be spoken.  Set here rather than
        // in the layout because it changes per row.
        // 0 is the sentinel, not null: SparseIntArray.get returns a
        // primitive, so a boxed result could never be null and an icon the
        // table does not know -- R.drawable.blank, which both subclasses fall
        // back to -- would reach getString(0) and throw.
        final int iconText = ICON_DESCRIPTIONS.get(iconId, 0);
        icon.setContentDescription(iconText == 0 ? null : getString(iconText));
        icon.setImportantForAccessibility(iconText == 0
            ? View.IMPORTANT_FOR_ACCESSIBILITY_NO
            : View.IMPORTANT_FOR_ACCESSIBILITY_YES);

        final String subTitle = getSubTitleFromItem(item);
        TextView subTitleView = (TextView) v.findViewById(R.id.sub_title);
        if (subTitle != null && subTitle != "") {
          subTitleView.setText(subTitle);
          subTitleView.setVisibility(View.VISIBLE);
        } else {
          subTitleView.setVisibility(View.GONE);
        }
      } else {
        // Otherwise give a loading indicator.
        v = vi.inflate(R.layout.progress, parent, false);
      }

      return v;
    }
  }

  protected enum ShowStatus {
    LOADED, LOADING, MISSING;
  }

  protected final static int MAX_SHOW_REQUEST_BATCH = 5;
  /**
   * Launches the screens that can change this list -- a sub-folder, or
   * Explore, where a show can be deleted.  Replaces
   * startActivityForResult()/onActivityResult() and the
   * EXPECT_REFRESH_INTENT_ID request code that went with them: with the
   * Activity Result API the launcher itself identifies the result, so there
   * is no request code to compare.  Registering in a field initializer is the
   * documented pattern -- it has to happen before the activity is started.
   */
  protected final ActivityResultLauncher<Intent> mRefreshLauncher =
      registerForActivityResult(
          new ActivityResultContracts.StartActivityForResult(),
          new ActivityResultCallback<ActivityResult>() {
            public void onActivityResult(ActivityResult result) {
              onRefreshResult(result);
            }
          });
  protected final JsonNode mDeletedItem = Utils
      .parseJson("{\"folderTransportType\":[\"deletedFolder\"]"
          + ",\"recordingFolderItemId\":\"deleted\""
          + ",\"title\":\"Recently Deleted\"}");
  protected MindRpcResponseListener mDetailCallback;
  /** A batch of details has already failed and been reported. */
  protected boolean mDetailFailed = false;
  protected String mFolderId;
  protected MindRpcResponseListener mIdSequenceCallback;
  protected ShowsAdapter mListAdapter;
  protected int mLongPressIndex;
  protected JsonNode mLongPressItem;
  protected String mOrderBy = "startTime";
  protected final String[] mOrderLabels = new String[] { "Date", "A-Z" };
  protected final String[] mOrderValues = new String[] { "startTime", "title" };
  protected int mRequestCount = 0;
  protected final SparseArray<ArrayList<Integer>> mRequestSlotMap =
      new SparseArray<ArrayList<Integer>>();
  protected final ArrayList<JsonNode> mShowData = new ArrayList<JsonNode>();
  protected ArrayNode mShowIds;
  protected final ArrayList<ShowStatus> mShowStatus =
      new ArrayList<ShowStatus>();

  protected final OnItemClickListener mOnClickListener =
      new OnItemClickListener() {
        public void onItemClick(AdapterView<?> parent, View view, int position,
            long id) {
          final JsonNode item = mShowData.get(position);
          if (item == null) {
            return;
          }

          final JsonNode countNode = item.path("folderItemCount");
          if (mDeletedItem == item
              || (countNode != null && countNode.asInt() > 0)) {
            // Navigate to 'my shows' for this folder.
            Intent intent = new Intent(ShowList.this, MyShows.class);
            intent.putExtra("folderId", item.path("recordingFolderItemId")
                .asText());
            intent.putExtra("folderName", item.path("title").asText());
            mRefreshLauncher.launch(intent);
          } else {
            final JsonNode recording = getRecordingFromItem(item);

            Intent intent = new Intent(ShowList.this, ExploreTabs.class);
            intent.putExtra("contentId", recording.path("contentId")
                .asText());
            intent.putExtra("collectionId", recording.path("collectionId")
                .asText());

            // Regular / deleted recordings IDs are differently located.
            if (item.has("childRecordingId")) {
              intent.putExtra("recordingId", item.path("childRecordingId")
                  .asText());
            } else if (item.has("recordingId")) {
              intent.putExtra("recordingId", item.path("recordingId")
                  .asText());
            }

            mRefreshLauncher.launch(intent);
          }
        }
      };

  protected abstract int getIconForItem(JsonNode item);

  protected abstract Pair<ArrayList<String>, ArrayList<Integer>> getLongPressChoices(
      JsonNode item);

  protected abstract JsonNode getRecordingFromItem(JsonNode item);

  protected abstract void startRequest();

  protected String getSubTitleFromItem(JsonNode item) {
    return null;
  };

  protected void finishWithRefresh() {
    setRefreshResult();
    finish();
  }

  /**
   * Did the box refuse a batch of row details?  If so, say so -- once, since
   * a long list asks in several batches and each would fail alike -- and
   * drop the batch.  Its rows keep their loading spinners rather than being
   * put back to MISSING, which would ask again on the very next redraw and
   * keep asking.
   */
  protected boolean isDetailError(MindRpcResponse response) {
    if (!Utils.isError(response)) {
      return false;
    }
    Utils.log("ShowList: details failed: " + Utils.errorText(response));
    mRequestSlotMap.remove(response.getRpcId());
    if (!mDetailFailed) {
      mDetailFailed = true;
      Utils.toast(this, R.string.error_list_failed, Toast.LENGTH_SHORT);
    }
    return true;
  }

  /** Handles a result from {@link #mRefreshLauncher}. */
  protected void onRefreshResult(ActivityResult result) {
    if (result.getResultCode() != Activity.RESULT_OK) {
      return;
    }

    final Intent data = result.getData();
    if (data != null && data.getBooleanExtra("refresh", false)) {
      setRefreshResult();
      if (mShowData.size() == 1) {
        // We deleted the last show! Go up a level.
        finishWithRefresh();
      } else {
        // Load the list of remaining shows.  setRefreshResult() already ran
        // above, for both branches.
        startRequest();
      }
    }
  }

  /**
   * What each row icon means, for a screen reader.  A SparseArray rather than
   * a switch because resource ids are not compile-time constants here.
   * Anything absent is decorative and is hidden from accessibility instead.
   */
  private static final android.util.SparseIntArray ICON_DESCRIPTIONS =
      new android.util.SparseIntArray();
  static {
    ICON_DESCRIPTIONS.put(R.drawable.folder, R.string.a11y_folder);
    ICON_DESCRIPTIONS.put(R.drawable.folder_recording,
        R.string.a11y_folder_recording);
    ICON_DESCRIPTIONS.put(R.drawable.folder_downloading,
        R.string.a11y_folder_downloading);
    ICON_DESCRIPTIONS.put(R.drawable.folder_wishlist,
        R.string.a11y_folder_wishlist);
    ICON_DESCRIPTIONS.put(R.drawable.recording, R.string.a11y_recording);
    ICON_DESCRIPTIONS.put(R.drawable.recording_recording,
        R.string.a11y_recording_now);
    ICON_DESCRIPTIONS.put(R.drawable.recording_downloading,
        R.string.a11y_downloading);
    ICON_DESCRIPTIONS.put(R.drawable.recording_expired, R.string.a11y_expired);
    ICON_DESCRIPTIONS.put(R.drawable.recording_expiressoon,
        R.string.a11y_expires_soon);
    ICON_DESCRIPTIONS.put(R.drawable.recording_keep, R.string.a11y_keep);
    ICON_DESCRIPTIONS.put(R.drawable.recording_suggestion,
        R.string.a11y_suggestion);
    ICON_DESCRIPTIONS.put(R.drawable.recording_wishlist,
        R.string.a11y_wishlist);
    ICON_DESCRIPTIONS.put(R.drawable.recording_deleted, R.string.a11y_deleted);
    ICON_DESCRIPTIONS.put(R.drawable.todo_seasonpass,
        R.string.a11y_season_pass);
    ICON_DESCRIPTIONS.put(R.drawable.todo_single_offer,
        R.string.a11y_single_offer);
    ICON_DESCRIPTIONS.put(R.drawable.todo_wishlist, R.string.a11y_wishlist);
  }

  public void onClick(DialogInterface dialog, int position) {
    final Pair<ArrayList<String>, ArrayList<Integer>> choices =
        getLongPressChoices(mLongPressItem);
    Integer action = choices.second.get(position);

    String recordingId = mLongPressItem.path("recordingId").asText();

    // Playing here is the one action that sends no RPC from this screen: the
    // Player owns the whole session, because it also owes the release.
    if (action == R.string.watch_here) {
      startActivity(Utils.playHereIntent(
          this, recordingId, mLongPressItem.path("title").asText()));
      return;
    }

    MindRpcRequest req = null;
    final MindRpcResponseListener reqListener =
        new MindRpcResponseListener() {
          public void onResponse(MindRpcResponse response) {
            setProgressIndicator(-1);
            if (Utils.isError(response)) {
              // Nothing changed, so there is nothing to re-load.
              Utils.toast(ShowList.this, R.string.error_change_failed,
                  Toast.LENGTH_SHORT);
              return;
            }
            // Now that it's probably changed, re-load the list.
            startRequest();
          }
        };
    final MindRpcResponseListener removeListener =
        new MindRpcResponseListener() {
          public void onResponse(MindRpcResponse response) {
            setProgressIndicator(-1);
            if (Utils.isError(response)) {
              // Removing the row would claim a delete that did not happen.
              Utils.toast(ShowList.this, R.string.error_change_failed,
                  Toast.LENGTH_SHORT);
              return;
            }
            mShowData.remove(mLongPressIndex);
            mShowIds.remove(mLongPressIndex);
            mShowStatus.remove(mLongPressIndex);
            if (mShowData.isEmpty()) {
              finishWithRefresh();
              return;
            }
            mListAdapter.notifyDataSetChanged();
          }
        };
    MindRpcResponseListener listener = reqListener;

    // if/else rather than switch: resource ids are not compile-time constants.
    if (action == R.string.delete || action == R.string.stop_recording_and_delete) {
      req = new RecordingUpdate(recordingId, "deleted");
      listener = removeListener;
      setRefreshResult();
    } else if (action == R.string.dont_record) {
      req = new RecordingUpdate(recordingId, "cancelled");
      listener = removeListener;
      setRefreshResult();
    } else if (action == R.string.stop_recording || action == R.string.undelete) {
      req = new RecordingUpdate(recordingId, "complete");
      setRefreshResult();
    } else if (action == R.string.watch_now) {
      req = new UiNavigate(recordingId);
      listener =
          new MindRpcResponseListener() {
            public void onResponse(MindRpcResponse response) {
              setProgressIndicator(-1);
              if (Utils.isError(response)) {
                Utils.toast(ShowList.this, R.string.error_change_failed,
                    Toast.LENGTH_SHORT);
                return;
              }
              Intent intent = new Intent(ShowList.this, NowShowing.class);
              startActivity(intent);
            }
          };
    }

    setProgressIndicator(1);
    MindRpc.addRequest(req, listener);
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    SharedPreferences sharedPrefs = PreferenceManager
        .getDefaultSharedPreferences(this);
    mOrderBy = sharedPrefs.getString("my_shows_order_by", mOrderBy);
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    Utils.createFullOptionsMenu(menu, this);
    return true;
  }

  public boolean onItemLongClick(AdapterView<?> parent, View view,
      int position, long id) {
    if (position > mShowData.size()) {
      return false;
    }
    if (mShowStatus.get(position) != ShowStatus.LOADED) {
      return false;
    }

    mLongPressIndex = position;
    mLongPressItem = mShowData.get(position);
    if (mLongPressItem.has("recordingForChildRecordingId")
        && !mLongPressItem.has("folderType")) {
      mLongPressItem = mLongPressItem.path("recordingForChildRecordingId");
    }

    final Pair<ArrayList<String>, ArrayList<Integer>> choices =
        getLongPressChoices(mLongPressItem);
    if (choices == null) {
      return false;
    }
    final ArrayAdapter<String> choicesAdapter =
        new ArrayAdapter<String>(this, android.R.layout.select_dialog_item,
            choices.first);

    Builder dialogBuilder = new AlertDialog.Builder(this);
    dialogBuilder.setTitle("Operation?");
    dialogBuilder.setAdapter(choicesAdapter, this);
    AlertDialog dialog = dialogBuilder.create();
    dialog.show();

    return true;
  }

  protected void setProgressIndicator(int change) {
    mRequestCount += change;
    Utils.showProgress(this, mRequestCount > 0);
  }

  protected void setRefreshResult() {
    Intent resultIntent = new Intent();
    resultIntent.putExtra("refresh", true);
    setResult(Activity.RESULT_OK, resultIntent);
  }
}
