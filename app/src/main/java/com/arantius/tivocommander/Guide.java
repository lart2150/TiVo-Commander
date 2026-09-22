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
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.arantius.tivocommander.rpc.MindRpc;
import com.arantius.tivocommander.rpc.request.GridRowSearch;
import com.arantius.tivocommander.rpc.request.RecordingUpdate;
import com.arantius.tivocommander.rpc.request.ScheduledOfferSearch;
import com.arantius.tivocommander.rpc.response.MindRpcResponse;
import com.arantius.tivocommander.rpc.response.MindRpcResponseListener;
import com.arantius.tivocommander.views.GuideScrollSync;
import com.arantius.tivocommander.views.SyncedHorizontalScrollView;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * The channel guide: a grid of channels down and time across.
 *
 * Both axes fill in as you go, because the whole grid is far too much to ask
 * for at once -- a box with 67 receivable channels has megabytes of listings
 * in a day.  Scrolling down past the loaded channels asks for the next
 * {@link GridRowSearch#PAGE_SIZE} of them; scrolling right to the end of the
 * loaded hours asks for another {@link #SPAN_STEP_HOURS} for the channels
 * already held.
 *
 * The rows are separate scrollers kept in step by a {@link GuideScrollSync}
 * rather than one wide surface -- see that class for why.
 */
public class Guide extends BaseActivity implements GuideScrollSync.Member {
  /** Optional intent extra: the time to open the grid on, in millis. */
  public static final String EXTRA_START_TIME = "startTime";

  /** Hours of listings fetched at a time, and initially. */
  private static final int SPAN_STEP_HOURS = 6;
  /** Total hours the grid will grow to before it stops extending. */
  private static final int SPAN_MAX_HOURS = 24;
  /** Minutes between ruler ticks. */
  private static final int TICK_MINUTES = 30;
  /**
   * Rows left below the last visible one before the next page is asked for.
   *
   * Roughly a screenful: a page is 20 channels and takes the best part of a
   * second, so asking at five rows of warning meant scrolling into empty space
   * and waiting.  Asking a screen early means the rows are usually there
   * before they are reached.
   */
  private static final int ROW_PREFETCH = 12;
  /**
   * Screenfuls of listings to keep loaded ahead of where the grid is scrolled.
   *
   * Same reasoning sideways.  Cheap to raise -- an extend is one request per
   * 20 loaded channels -- and the span stops growing at
   * {@link #SPAN_MAX_HOURS} either way.
   */
  private static final int SPAN_PREFETCH_SCREENS = 3;
  /**
   * Most To Do pages to read before giving up.
   *
   * recordingSearch is asked by count and offset with no order pinned, so a
   * result set that shifts underneath could keep answering full pages for
   * ever.  Far more than any real To Do list.
   */
  private static final int MAX_SCHEDULED_PAGES = 40;

  /** One channel, and the listings loaded for it so far. */
  private static class Row {
    final JsonNode channel;
    final List<JsonNode> offers = new ArrayList<JsonNode>();
    /** Offer ids already placed, so an extend cannot double up a program. */
    final Set<String> offerIds = new HashSet<String>();

    Row(JsonNode channel) {
      this.channel = channel;
    }

    void add(JsonNode offer) {
      String id = offer.path("offerId").asText();
      if (!"".equals(id) && !offerIds.add(id)) {
        return;
      }
      offers.add(offer);
    }
  }

  private final List<Row> mRows = new ArrayList<Row>();
  /** offerId -> recordingId, for every program the box means to record. */
  private final Map<String, String> mScheduled = new HashMap<String, String>();
  private final GuideScrollSync mSync = new GuideScrollSync();

  private Date mSpanStart;
  private int mLoadedHours = 0;
  private int mMinuteWidth;
  private int mBlockGap;
  private int mBlockPadding;
  private int mMinTitleWidth;
  private int mCheckSize;
  private RowAdapter mAdapter;
  private RecyclerView mList;
  /** Set while a page is in flight, so scrolling cannot ask for it twice. */
  private boolean mLoadingRows = false;
  private boolean mLoadingSpan = false;
  /** Cleared when the box answers with a short page: there are no more. */
  private boolean mMoreRows = true;
  /** Set when a channel page was refused, to say so rather than show blank. */
  private boolean mLoadFailed = false;

  /**
   * Launches Explore and SubscribeOffer, either of which can change whether a
   * program is going to record.  Rather than reload the grid -- which would
   * cost every page again -- only the scheduled set is re-read, and the rows
   * redraw from it.
   */
  private final ActivityResultLauncher<Intent> mRefreshLauncher =
      registerForActivityResult(
          new ActivityResultContracts.StartActivityForResult(),
          new ActivityResultCallback<ActivityResult>() {
            public void onActivityResult(ActivityResult result) {
              loadScheduled();
            }
          });

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    // The extras have to be handed over, not dropped: a cold start is not
    // connected yet, so MindRpc bounces through the Connect screen and
    // relaunches this one from exactly what it was given here.  Passing null
    // loses the start time on precisely the launch that has one.
    if (MindRpc.init(this, getIntent().getExtras())) {
      return;
    }

    setContent(R.layout.guide);
    setTitle(R.string.guide);

    mMinuteWidth =
        getResources().getDimensionPixelSize(R.dimen.guide_minute_width);
    mBlockGap = getResources().getDimensionPixelSize(R.dimen.guide_block_gap);
    mBlockPadding =
        getResources().getDimensionPixelSize(R.dimen.guide_block_padding);
    mMinTitleWidth =
        getResources().getDimensionPixelSize(R.dimen.guide_min_title_width);
    mCheckSize = getResources().getDimensionPixelSize(R.dimen.guide_check_size);
    mSpanStart = floorToTick(new Date(startTimeExtra()));

    mAdapter = new RowAdapter();
    mList = findViewById(R.id.guide_rows);
    mList.setLayoutManager(new LinearLayoutManager(this));
    mList.setAdapter(mAdapter);
    mList.addOnScrollListener(new RecyclerView.OnScrollListener() {
      @Override
      public void onScrolled(@NonNull RecyclerView view, int dx, int dy) {
        maybeLoadMoreRows();
      }
    });

    SyncedHorizontalScrollView ruler = findViewById(R.id.guide_ruler_scroll);
    ruler.setSync(mSync);
    // The activity joins the sync too, not to be scrolled but so it learns
    // when the right edge of the loaded hours has come into view.
    mSync.register(this);

    ((TextView) findViewById(R.id.guide_day)).setText(formatDay(mSpanStart));

    loadScheduled();
    loadNextRowPage();
  }

  /* ---- time and geometry ---- */

  /**
   * The moment the grid should open on: "now", unless the caller named one.
   *
   * The extra is what lets the screen be pointed at a particular evening
   * rather than always at the present -- which is how the tests drive it
   * against a recorded page of listings, instead of a capture that would stop
   * matching "now" the day after it was taken.
   */
  private long startTimeExtra() {
    Bundle extras = getIntent().getExtras();
    long when = extras == null ? 0 : extras.getLong(EXTRA_START_TIME, 0);
    return when > 0 ? when : System.currentTimeMillis();
  }

  /** The tick at or before this moment: where the loaded span begins. */
  private static Date floorToTick(Date when) {
    long tick = TICK_MINUTES * 60L * 1000L;
    return new Date(when.getTime() / tick * tick);
  }

  private Date spanEnd() {
    return new Date(mSpanStart.getTime() + hoursMs(mLoadedHours));
  }

  private static long hoursMs(int hours) {
    return hours * 60L * 60L * 1000L;
  }

  /** Pixels from the left edge of the span to a moment in it. */
  private int xForTime(long when) {
    long minutes = (when - mSpanStart.getTime()) / 60000L;
    return (int) (minutes * mMinuteWidth);
  }

  private int spanWidth() {
    return mLoadedHours * 60 * mMinuteWidth;
  }

  private static String formatDay(Date when) {
    SimpleDateFormat format = new SimpleDateFormat("EEE\nM/d", Locale.US);
    format.setTimeZone(TimeZone.getDefault());
    return format.format(when);
  }

  /**
   * Built once.  formatClock runs for every program drawn, and a fresh
   * SimpleDateFormat per block was a real share of the cost of a redraw.
   * Safe to share: all of this runs on the main thread.
   */
  private static final SimpleDateFormat CLOCK =
      new SimpleDateFormat("h:mm a", Locale.US);

  private static String formatClock(Date when) {
    CLOCK.setTimeZone(TimeZone.getDefault());
    return CLOCK.format(when);
  }

  /* ---- loading ---- */

  /**
   * Read every scheduled recording, a page at a time, and index it by offer
   * id.  Each page asks for the next until a short one comes back.
   */
  private void loadScheduled() {
    mScheduled.clear();
    requestScheduledPage(0);
  }

  private void requestScheduledPage(final int offset) {
    // A token of its own rather than a field shared with every other request:
    // the progress bar counts owners, so reusing one makes the first response
    // to land hide the bar on behalf of all the others still in flight.
    final Object token = new Object();
    Utils.showProgress(this, token, true);
    MindRpc.addRequest(new ScheduledOfferSearch(offset),
        new MindRpcResponseListener() {
          public void onResponse(MindRpcResponse response) {
            Utils.showProgress(Guide.this, token, false);
            if (Utils.isError(response)) {
              // Keep whatever pages did arrive; the marks are then merely
              // incomplete rather than wrong.
              Utils.log("Guide: scheduled page failed: "
                  + Utils.errorText(response));
              return;
            }
            JsonNode recordings = response.getBody().path("recording");
            for (int i = 0; i < recordings.size(); i++) {
              JsonNode recording = recordings.path(i);
              String offerId = recording.path("offerId").asText();
              if (!"".equals(offerId)) {
                mScheduled.put(offerId, recording.path("recordingId").asText());
              }
            }
            if (recordings.size() >= ScheduledOfferSearch.PAGE_SIZE) {
              requestScheduledPage(offset + ScheduledOfferSearch.PAGE_SIZE);
            } else if (mAdapter != null) {
              // The marks are drawn from this map, so every row already on
              // screen has to be given the chance to redraw.
              redrawRows();
            }
          }
        });
  }

  /** Ask for the next block of channels, anchored after the last one loaded. */
  private void loadNextRowPage() {
    if (mLoadingRows || !mMoreRows) {
      return;
    }
    mLoadingRows = true;
    final Object token = new Object();
    Utils.showProgress(this, token, true);

    // The first page has no anchor, which starts it at the top of the lineup.
    final boolean first = mRows.isEmpty();
    JsonNode anchor = first ? null : mRows.get(mRows.size() - 1).channel;
    if (first) {
      mLoadedHours = SPAN_STEP_HOURS;
    }
    // The span can grow while this is in flight -- the more so now that both
    // axes fetch early -- and these channels would then hold listings only up
    // to where it used to end, with nothing ever going back for the rest.
    final int spanAtRequest = mLoadedHours;
    final Date endAtRequest = spanEnd();

    MindRpc.addRequest(new GridRowSearch(anchor, mSpanStart, endAtRequest),
        new MindRpcResponseListener() {
          public void onResponse(MindRpcResponse response) {
            mLoadingRows = false;
            Utils.showProgress(Guide.this, token, false);
            if (Utils.isError(response)) {
              // Leave mMoreRows alone: this page failed, the lineup did not
              // end.  Scrolling again retries rather than giving up for the
              // life of the screen.
              Utils.log("Guide: channel page failed: "
                  + Utils.errorText(response));
              mLoadFailed = true;
              showEmptyIfNothing();
              return;
            }

            JsonNode gridRows = response.getBody().path("gridRow");
            int added = 0;
            for (int i = 0; i < gridRows.size(); i++) {
              // Every page but the first repeats its anchor as row 0.
              if (!first && i == 0) {
                continue;
              }
              JsonNode gridRow = gridRows.path(i);
              Row row = new Row(gridRow.path("channel"));
              JsonNode offers = gridRow.path("offer");
              for (int j = 0; j < offers.size(); j++) {
                row.add(offers.path(j));
              }
              mRows.add(row);
              added++;
            }

            // The end of the lineup is a page the box could not fill, not
            // merely one this screen took nothing from: an anchor-only page
            // can also happen when the lineup shifts underneath, and treating
            // that as the end would stop channel paging for good.
            if (gridRows.size() < GridRowSearch.PAGE_SIZE) {
              mMoreRows = false;
            }
            mLoadFailed = false;
            if (first) {
              buildRuler();
            }
            if (added > 0) {
              mAdapter.notifyItemRangeInserted(mRows.size() - added, added);
              if (mLoadedHours > spanAtRequest) {
                // The span grew while this was in flight; catch these
                // channels up to it.
                fillRows(mRows.get(mRows.size() - added).channel,
                    endAtRequest, spanEnd());
              }
            }
            showEmptyIfNothing();
            // The first page may not fill a tall screen on its own.
            maybeLoadMoreRows();
          }
        });
  }

  /**
   * Rebind every row that is on screen.
   *
   * The rows themselves have not come or gone -- what changed is something
   * they all draw from: the scheduled set, or the width of the loaded span.
   */
  private void redrawRows() {
    if (mAdapter == null || mRows.isEmpty()) {
      return;
    }
    if (mList != null && (mList.isComputingLayout() || mList.isAnimating())) {
      // This can be reached from a scroll callback, and a scroll callback can
      // fire from inside RecyclerView's own layout pass -- a row being laid
      // out narrower than the grid's position reports the clamp.  Notifying
      // there throws ("Cannot call this method while RecyclerView is
      // computing a layout or scrolling"), so it waits for the pass to end.
      mList.post(new Runnable() {
        public void run() {
          redrawRows();
        }
      });
      return;
    }
    mAdapter.notifyItemRangeChanged(0, mRows.size());
  }

  private void maybeLoadMoreRows() {
    if (mLoadingRows || !mMoreRows || mList == null) {
      return;
    }
    LinearLayoutManager layout = (LinearLayoutManager) mList.getLayoutManager();
    if (layout == null) {
      return;
    }
    if (layout.findLastVisibleItemPosition() >= mRows.size() - ROW_PREFETCH) {
      loadNextRowPage();
    }
  }

  /**
   * Fetch another {@link #SPAN_STEP_HOURS} for the channels already loaded.
   *
   * The lineup comes back in a stable order, so each page of the extend is
   * anchored at the row before the page it is filling, exactly as the first
   * load walked it; the offers are then merged into rows by channel identity
   * rather than by position, so a lineup that shifted underneath cannot put
   * one channel's programs under another's name.
   */
  private void loadNextSpan() {
    if (mLoadingSpan || mRows.isEmpty() || mLoadedHours >= SPAN_MAX_HOURS) {
      return;
    }
    mLoadingSpan = true;

    final Date from = spanEnd();
    final Date to = new Date(from.getTime() + hoursMs(SPAN_STEP_HOURS));
    mLoadedHours += SPAN_STEP_HOURS;
    // Widen the ruler and the rows straight away, so the grid does not stop
    // dead at the old edge while the new listings are in flight.
    buildRuler();
    redrawRows();

    final int pages =
        (mRows.size() + GridRowSearch.PAGE_SIZE - 1) / GridRowSearch.PAGE_SIZE;
    final int[] outstanding = new int[] { pages };
    final boolean[] anySucceeded = new boolean[] { false };
    for (int page = 0; page < pages; page++) {
      // Anchored ON this page's first row, not the row before it.  The anchor
      // is inclusive, so a page covers exactly the PAGE_SIZE rows from there
      // and nothing is skipped.  Paging uses the other convention -- anchor
      // the last row already held and drop the repeat -- and borrowing it here
      // quietly cost one channel per page: with 60 loaded, rows 39 and 59
      // were never extended and stayed blank past the old edge.
      JsonNode anchor = mRows.get(page * GridRowSearch.PAGE_SIZE).channel;
      final Object token = new Object();
      Utils.showProgress(this, token, true);
      MindRpc.addRequest(new GridRowSearch(anchor, from, to),
          new MindRpcResponseListener() {
            public void onResponse(MindRpcResponse response) {
              Utils.showProgress(Guide.this, token, false);
              if (Utils.isError(response)) {
                Utils.log("Guide: span page failed: "
                    + Utils.errorText(response));
              } else {
                anySucceeded[0] = true;
                JsonNode gridRows = response.getBody().path("gridRow");
                for (int i = 0; i < gridRows.size(); i++) {
                  JsonNode gridRow = gridRows.path(i);
                  Row row = rowForChannel(gridRow.path("channel"));
                  if (row == null) {
                    continue;
                  }
                  JsonNode offers = gridRow.path("offer");
                  for (int j = 0; j < offers.size(); j++) {
                    row.add(offers.path(j));
                  }
                }
              }
              // The span is released once every page has reported back,
              // successfully or not.
              if (--outstanding[0] == 0) {
                mLoadingSpan = false;
                if (!anySucceeded[0]) {
                  // Nothing arrived, so take the hours back rather than leave
                  // a widened ruler over a band that will never be filled --
                  // the next extend would start beyond it.
                  mLoadedHours -= SPAN_STEP_HOURS;
                  buildRuler();
                }
                redrawRows();
              }
            }
          });
    }
  }

  /**
   * Fetch one stretch of time for channels that are behind the loaded span.
   *
   * Merged by channel identity, and the anchor row is itself wanted here --
   * unlike a paging request, where it repeats a row the previous page already
   * brought.  Offers that arrive twice are dropped by {@link Row#add}.
   */
  private void fillRows(JsonNode anchor, Date from, Date to) {
    final Object token = new Object();
    Utils.showProgress(this, token, true);
    MindRpc.addRequest(new GridRowSearch(anchor, from, to),
        new MindRpcResponseListener() {
          public void onResponse(MindRpcResponse response) {
            Utils.showProgress(Guide.this, token, false);
            if (Utils.isError(response)) {
              Utils.log("Guide: catch-up page failed: "
                  + Utils.errorText(response));
              return;
            }
            JsonNode gridRows = response.getBody().path("gridRow");
            for (int i = 0; i < gridRows.size(); i++) {
              JsonNode gridRow = gridRows.path(i);
              Row row = rowForChannel(gridRow.path("channel"));
              if (row == null) {
                continue;
              }
              JsonNode offers = gridRow.path("offer");
              for (int j = 0; j < offers.size(); j++) {
                row.add(offers.path(j));
              }
            }
            redrawRows();
          }
        });
  }

  /**
   * The loaded row for a channel, matched on the identity the lineup gave it.
   *
   * Matched by station rather than by number: a number alone is not unique
   * across sources.
   */
  private Row rowForChannel(JsonNode channel) {
    String stationId = channel.path("stationId").asText();
    String number = channel.path("channelNumber").asText();
    for (int i = 0; i < mRows.size(); i++) {
      JsonNode mine = mRows.get(i).channel;
      if (!"".equals(stationId)) {
        if (stationId.equals(mine.path("stationId").asText())) {
          return mRows.get(i);
        }
      } else if (number.equals(mine.path("channelNumber").asText())) {
        return mRows.get(i);
      }
    }
    return null;
  }

  private void showEmptyIfNothing() {
    TextView empty = findViewById(R.id.guide_empty);
    boolean nothing = mRows.isEmpty() && !mLoadingRows;
    // A refusal and an empty lineup look identical once the grid is blank, so
    // say which it was -- there are no rows here to scroll and prompt a retry.
    empty.setText(mLoadFailed ? R.string.guide_failed : R.string.guide_empty);
    empty.setVisibility(nothing ? View.VISIBLE : View.GONE);
  }

  /* ---- the ruler ---- */

  private void buildRuler() {
    LinearLayout ruler = findViewById(R.id.guide_ruler);
    ruler.removeAllViews();
    int ticks = mLoadedHours * 60 / TICK_MINUTES;
    for (int i = 0; i < ticks; i++) {
      Date at = new Date(mSpanStart.getTime() + i * TICK_MINUTES * 60000L);
      TextView label = new TextView(this);
      label.setText(formatClock(at));
      label.setTextColor(
          getResources().getColor(R.color.guide_ruler_text, getTheme()));
      label.setLayoutParams(new LinearLayout.LayoutParams(
          TICK_MINUTES * mMinuteWidth, ViewGroup.LayoutParams.WRAP_CONTENT));
      ruler.addView(label);
    }
  }

  /* ---- horizontal position ---- */

  /**
   * The grid scrolled sideways.  Nothing of this activity's own moves -- this
   * is where the titles are kept in view, and where it learns that the end of
   * the loaded hours is coming up.
   */
  public void setSyncedScrollX(int scrollX) {
    keepTitlesInView(scrollX);
    showDayAt(scrollX);
    View content = findViewById(R.id.guide_rows);
    int visible = content == null ? 0 : content.getWidth();
    // Several screenfuls of slack, so the next hours are in hand well before
    // the edge is reached rather than just as it is.
    if (scrollX + visible * (1 + SPAN_PREFETCH_SCREENS) >= spanWidth()) {
      loadNextSpan();
    }
  }

  /**
   * Name the day actually on screen, not the one the grid opened on.
   *
   * The span runs to {@link #SPAN_MAX_HOURS}, so scrolling right crosses
   * midnight; the header beside the ruler is the only thing saying which day
   * the times belong to, and left at the opening day it contradicts them.
   */
  private void showDayAt(int scrollX) {
    TextView day = findViewById(R.id.guide_day);
    if (day == null || mMinuteWidth == 0) {
      return;
    }
    long minutes = scrollX / mMinuteWidth;
    String text = formatDay(new Date(mSpanStart.getTime() + minutes * 60000L));
    if (!text.contentEquals(day.getText())) {
      day.setText(text);
    }
  }

  /**
   * Slide each title along so it stays on screen while its program does.
   *
   * A two and a half hour film is over a screen wide, and its title is drawn
   * at the start of the block -- so once you scroll into the middle of it the
   * block reads as a blank gap, which is exactly what it looks like when a
   * channel has no listings at all.  Nudging the text inside the block instead
   * keeps it labelled all the way across.
   *
   * This runs on every scroll frame, so it touches only the rows the list
   * currently has laid out and writes only when the value actually changes.
   */
  private void keepTitlesInView(int scrollX) {
    if (mList == null) {
      return;
    }
    for (int i = 0; i < mList.getChildCount(); i++) {
      FrameLayout blocks =
          mList.getChildAt(i).findViewById(R.id.guide_row_blocks);
      if (blocks == null) {
        continue;
      }
      for (int j = 0; j < blocks.getChildCount(); j++) {
        slideTitle(blocks.getChildAt(j), scrollX);
      }
    }
  }

  /** Offset one block's text so it sits at the visible edge of its block. */
  private void slideTitle(View block, int scrollX) {
    // The laid out position is not available while a row is still being
    // bound, but the margin and width it was given are, and they are the same
    // numbers.
    FrameLayout.LayoutParams params =
        (FrameLayout.LayoutParams) block.getLayoutParams();
    int shift = scrollX - params.leftMargin;
    // Never before the block starts, and never so far that the title is
    // pushed off the block's own end.
    shift = Math.max(0, Math.min(shift, params.width - mMinTitleWidth));
    // Padding rather than a translation: the background is painted over the
    // whole view either way, so only the text moves, and the text stays
    // clipped to its own block instead of spilling onto its neighbour.
    if (block.getPaddingLeft() != mBlockPadding + shift) {
      block.setPadding(mBlockPadding + shift, block.getPaddingTop(),
          mBlockPadding, block.getPaddingBottom());
    }
  }

  /* ---- rows ---- */

  private class RowHolder extends RecyclerView.ViewHolder {
    final TextView channel;
    final SyncedHorizontalScrollView scroll;
    final FrameLayout blocks;

    RowHolder(View view) {
      super(view);
      channel = view.findViewById(R.id.guide_channel);
      scroll = view.findViewById(R.id.guide_row_scroll);
      blocks = view.findViewById(R.id.guide_row_blocks);
    }
  }

  private class RowAdapter extends RecyclerView.Adapter<RowHolder> {
    @NonNull
    @Override
    public RowHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
      return new RowHolder(LayoutInflater.from(parent.getContext())
          .inflate(R.layout.item_guide_row, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RowHolder holder, int position) {
      Row row = mRows.get(position);
      JsonNode channel = row.channel;
      String number = channel.path("channelNumber").asText();
      String callSign = channel.path("callSign").asText();
      holder.channel.setText(
          getString(R.string.guide_channel_label, number, callSign));
      holder.channel.setContentDescription(
          getString(R.string.a11y_guide_channel, number, callSign));

      // Reused rather than rebuilt.  Tearing the row down and re-inflating a
      // view per program on every bind put roughly a hundred inflations on
      // the main thread for one screen of rows, and redrawRows() does that
      // for every visible row at once -- often enough, while scrolling hard,
      // to stop the app answering input.
      FrameLayout.LayoutParams rowParams =
          (FrameLayout.LayoutParams) holder.blocks.getLayoutParams();
      if (rowParams == null || rowParams.width != spanWidth()) {
        holder.blocks.setLayoutParams(new FrameLayout.LayoutParams(spanWidth(),
            ViewGroup.LayoutParams.MATCH_PARENT));
      }
      int used = 0;
      for (int i = 0; i < row.offers.size(); i++) {
        if (bindBlock(holder.blocks, used, row.offers.get(i))) {
          used++;
        }
      }
      // Anything left from a busier row this view held before.
      while (holder.blocks.getChildCount() > used) {
        holder.blocks.removeViewAt(holder.blocks.getChildCount() - 1);
      }

      // Last, so a recycled row lands where the rest of the grid is scrolled
      // to before it is shown, rather than jumping there afterwards.
      holder.scroll.setSync(mSync);
    }

    @Override
    public int getItemCount() {
      return mRows.size();
    }
  }

  /**
   * Put one program into a row at the given slot, sized by its air time.
   *
   * Takes over the view already in that slot when there is one, so a rebind
   * costs a few setters rather than an inflate per program.
   *
   * @return whether a slot was used; false when the program draws nothing
   *     because it falls outside the loaded span.
   */
  private boolean bindBlock(FrameLayout parent, int slot,
      final JsonNode offer) {
    Date start = Utils.parseDateTimeStr(offer.path("startTime").asText());
    if (start == null) {
      return false;
    }
    long end = start.getTime() + offer.path("duration").asLong() * 1000L;

    // A program already running when the span starts is clipped to the left
    // edge rather than hung off it, so it still reads as "on now".
    int left = Math.max(0, xForTime(start.getTime()));
    int right = Math.min(spanWidth(), xForTime(end));
    int width = right - left - mBlockGap;
    if (width <= 0) {
      return false;
    }

    View block;
    if (slot < parent.getChildCount()) {
      block = parent.getChildAt(slot);
    } else {
      block =
          getLayoutInflater().inflate(R.layout.item_guide_offer, parent, false);
      parent.addView(block);
    }
    FrameLayout.LayoutParams params =
        new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT);
    params.leftMargin = left;
    block.setLayoutParams(params);

    final String offerId = offer.path("offerId").asText();
    final boolean scheduled = mScheduled.containsKey(offerId);
    block.setBackgroundResource(
        scheduled ? R.drawable.guide_block_scheduled : R.drawable.guide_block);

    TextView title = block.findViewById(R.id.guide_offer_title);
    title.setText(offer.path("title").asText());
    // The check repeats what the colour says, for anyone who cannot use it.
    // Its bounds are set rather than taken from the bitmap: at its own size it
    // stretches the title line and pushes the second line out of the block.
    Drawable check = null;
    if (scheduled) {
      check = ContextCompat.getDrawable(this, R.drawable.check);
      if (check != null) {
        check.setBounds(0, 0, mCheckSize, mCheckSize);
      }
    }
    title.setCompoundDrawables(check, null, null, null);
    title.setCompoundDrawablePadding(check == null ? 0 : mBlockGap * 2);

    TextView detail = block.findViewById(R.id.guide_offer_detail);
    detail.setText(detailFor(offer, start));

    block.setContentDescription(describe(offer, start, end, scheduled));
    block.setOnClickListener(new View.OnClickListener() {
      public void onClick(View view) {
        Intent intent = new Intent(Guide.this, ExploreTabs.class);
        intent.putExtra("contentId", offer.path("contentId").asText());
        intent.putExtra("collectionId", offer.path("collectionId").asText());
        intent.putExtra("offerId", offerId);
        mRefreshLauncher.launch(intent);
      }
    });
    block.setOnLongClickListener(new View.OnLongClickListener() {
      public boolean onLongClick(View view) {
        promptRecord(offer);
        return true;
      }
    });

    // A block placed while the grid is already scrolled has to be offset to
    // match, or its title sits off screen until the next scroll event.
    slideTitle(block, mSync.getScrollX());
    return true;
  }

  /** The second line of a block: the episode, or when it starts. */
  private String detailFor(JsonNode offer, Date start) {
    String subtitle = offer.path("subtitle").asText();
    if (!"".equals(subtitle)) {
      return subtitle;
    }
    return formatClock(start);
  }

  private String describe(JsonNode offer, Date start, long end,
      boolean scheduled) {
    StringBuilder out = new StringBuilder(offer.path("title").asText());
    String subtitle = offer.path("subtitle").asText();
    if (!"".equals(subtitle)) {
      out.append(", ").append(subtitle);
    }
    out.append(", ").append(getString(R.string.a11y_guide_time,
        formatClock(start), formatClock(new Date(end))));
    if (scheduled) {
      out.append(", ").append(getString(R.string.a11y_scheduled));
    }
    return out.toString();
  }

  /* ---- recording ---- */

  private void promptRecord(final JsonNode offer) {
    final String offerId = offer.path("offerId").asText();
    final String recordingId = mScheduled.get(offerId);
    final boolean scheduled = recordingId != null;

    final ArrayList<String> choices = new ArrayList<String>();
    choices.add(getString(scheduled ? R.string.dont_record : R.string.record));

    ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
        android.R.layout.select_dialog_item, choices);
    DialogInterface.OnClickListener onClick =
        new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int which) {
            if (scheduled) {
              cancelRecording(offerId, recordingId);
            } else {
              Intent intent =
                  new Intent(getBaseContext(), SubscribeOffer.class);
              intent.putExtra("offerId", offerId);
              intent.putExtra("contentId", offer.path("contentId").asText());
              mRefreshLauncher.launch(intent);
            }
          }
        };

    AlertDialog.Builder builder = new AlertDialog.Builder(this);
    builder.setTitle(offer.path("title").asText());
    builder.setAdapter(adapter, onClick);
    builder.create().show();
  }

  private void cancelRecording(final String offerId, String recordingId) {
    final Object token = new Object();
    Utils.showProgress(this, token, true);
    MindRpc.addRequest(new RecordingUpdate(recordingId, "cancelled"),
        new MindRpcResponseListener() {
          public void onResponse(MindRpcResponse response) {
            Utils.showProgress(Guide.this, token, false);
            if (Utils.isError(response)) {
              // The box refused -- a stale recordingId, most likely.  Dropping
              // the mark here would claim the recording had been cancelled
              // when it is still going to happen.
              Utils.toast(Guide.this,
                  getString(R.string.guide_cancel_failed),
                  Toast.LENGTH_SHORT);
              return;
            }
            // Drop the mark straight away rather than re-reading the whole To
            // Do list for the one row that changed.
            mScheduled.remove(offerId);
            redrawRows();
          }
        });
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
    Utils.log("Activity:Pause:Guide");
  }

  @Override
  protected void onResume() {
    super.onResume();
    Utils.log("Activity:Resume:Guide");
    MindRpc.init(this, getIntent().getExtras());
  }
}
