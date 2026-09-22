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
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
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
 * in a day, and the guide scrolls {@link #DAYS_AHEAD} days out.
 *
 * Time is a window, not a strip that only grows.  {@link #mSpanStart} to
 * {@link #mSpanEnd} is what is loaded, and what every row is as wide as; it
 * gains {@link #SPAN_STEP_HOURS} at whichever end is being scrolled towards
 * and, once past {@link #SPAN_MAX_HOURS}, gives back as much at the other end.
 * Giving hours back at the start moves every program in the grid sideways, so
 * the scroll position is moved with it and nothing appears to shift -- and the
 * rows carry no scrollbars to give the window away.  That is what lets the
 * grid be scrolled from the start of today to the end of the box's guide
 * data while never holding much more than a day of listings.
 *
 * Channels are a list that outlives the screen: {@link ChannelCache} keeps the
 * lineup per TiVo, so re-opening the guide draws the channel column at once
 * and asks only for the listings of the rows in view.  Only when no lineup is
 * cached is it discovered the slow way, a page of channels at a time.
 *
 * The rows are separate scrollers kept in step by a {@link GuideScrollSync}
 * rather than one wide surface -- see that class for why.
 */
public class Guide extends BaseActivity implements GuideScrollSync.Member {
  /** Optional intent extra: the time to open the grid on, in millis. */
  public static final String EXTRA_START_TIME = "startTime";

  /** Hours of listings fetched at a time. */
  private static final int SPAN_STEP_HOURS = 6;
  /** Hours the loaded window is trimmed back towards once it grows past. */
  private static final int SPAN_MAX_HOURS = 24;
  /**
   * Hours loaded behind the moment the grid opens on.
   *
   * A scroller sitting at its left edge cannot be dragged any further that
   * way, so without something already loaded behind the opening moment there
   * would be no way to start scrolling back into the earlier part of the day
   * at all.
   */
  private static final int LOOK_BACK_HOURS = 2;
  /** Hours either side of the viewport that a trim will not touch. */
  private static final int KEEP_LOADED_HOURS = 6;
  /**
   * Days past today the grid will scroll to.
   *
   * Set to roughly what a box actually holds: a real one checked while this
   * was written had listings about ten days out and nothing beyond, so going
   * further would only offer days that come up blank.
   */
  private static final int DAYS_AHEAD = 10;
  /** Minutes between ruler ticks. */
  private static final int TICK_MINUTES = 30;
  private static final long TICK_MS = TICK_MINUTES * 60L * 1000L;
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
   * 20 loaded channels.
   */
  private static final int SPAN_PREFETCH_SCREENS = 3;
  /**
   * And behind it.  Smaller: going back over the part of the day already gone
   * is the rarer move, and every hour read behind is one more to hold or trim.
   */
  private static final int SPAN_PREFETCH_SCREENS_BACK = 1;
  /**
   * Most To Do pages to read before giving up.
   *
   * recordingSearch is asked by count and offset with no order pinned, so a
   * result set that shifts underneath could keep answering full pages for
   * ever.  Far more than any real To Do list.
   */
  private static final int MAX_SCHEDULED_PAGES = 40;

  /**
   * One programme in a row, with its air time worked out once.
   *
   * {@link Utils#parseDateTimeStr} builds a SimpleDateFormat per call, and
   * these two times are wanted on every bind of every block and again on every
   * offer of every row each time the span is trimmed.  A day of listings for
   * sixty channels is thousands of formatters, built on the main thread while
   * the grid is being flung.
   */
  private static class Airing {
    final JsonNode offer;
    final long start;
    final long end;

    Airing(JsonNode offer, Date start) {
      this.offer = offer;
      this.start = start.getTime();
      this.end = this.start + offer.path("duration").asLong() * 1000L;
    }
  }

  /** One channel, the listings held for it, and the stretch they cover. */
  private static class Row {
    final JsonNode channel;
    final List<Airing> offers = new ArrayList<Airing>();
    /** Offer ids already placed, so an extend cannot double up a program. */
    final Set<String> offerIds = new HashSet<String>();
    /** The stretch these listings are complete for; null when there are none. */
    Date loadedFrom;
    Date loadedTo;
    /** Set while a request that will fill this row is in flight. */
    boolean loading;

    Row(JsonNode channel) {
      this.channel = channel;
    }

    void add(JsonNode offer) {
      Date start = Utils.parseDateTimeStr(offer.path("startTime").asText());
      if (start == null) {
        // Nothing can be drawn for a programme with no start, and keeping it
        // would only mean parsing it again to find that out.
        return;
      }
      String id = offer.path("offerId").asText();
      if (!"".equals(id) && !offerIds.add(id)) {
        return;
      }
      offers.add(new Airing(offer, start));
    }

    /** Are this row's listings complete across the whole of a window? */
    boolean covers(Date from, Date to) {
      return loadedFrom != null && !loadedFrom.after(from)
          && !loadedTo.before(to);
    }

    /**
     * Record a stretch as loaded, provided it joins what is already held.
     *
     * A stretch that does not touch the existing one would also be claiming
     * the gap between them, so it is not recorded at all: the row then reads
     * as incomplete and is asked for in full, which is the right answer.
     */
    void note(Date from, Date to) {
      if (loadedFrom == null) {
        loadedFrom = from;
        loadedTo = to;
        return;
      }
      if (from.after(loadedTo) || to.before(loadedFrom)) {
        return;
      }
      if (from.before(loadedFrom)) {
        loadedFrom = from;
      }
      if (to.after(loadedTo)) {
        loadedTo = to;
      }
    }

    /** Let go of everything outside a window the grid has narrowed to. */
    void trimTo(Date from, Date to) {
      Iterator<Airing> airings = offers.iterator();
      while (airings.hasNext()) {
        Airing airing = airings.next();
        if (airing.end <= from.getTime() || airing.start >= to.getTime()) {
          offerIds.remove(airing.offer.path("offerId").asText());
          airings.remove();
        }
      }
      if (loadedFrom == null) {
        return;
      }
      if (loadedFrom.before(from)) {
        loadedFrom = from;
      }
      if (loadedTo.after(to)) {
        loadedTo = to;
      }
      if (!loadedFrom.before(loadedTo)) {
        loadedFrom = null;
        loadedTo = null;
      }
    }

    /** Forget the listings entirely: the grid has moved to another day. */
    void clear() {
      offers.clear();
      offerIds.clear();
      loadedFrom = null;
      loadedTo = null;
      loading = false;
    }
  }

  private final List<Row> mRows = new ArrayList<Row>();
  /** offerId -> recordingId, for every program the box means to record. */
  private final Map<String, String> mScheduled = new HashMap<String, String>();
  private final GuideScrollSync mSync = new GuideScrollSync();

  /** Midnight at the start of today: the furthest back the grid will go. */
  private Date mDayStart;
  /** Midnight after the last day the grid will go forward to. */
  private Date mLimitEnd;
  /** The window of time currently loaded, and the width of every row. */
  private Date mSpanStart;
  private Date mSpanEnd;
  private int mMinuteWidth;
  private int mBlockGap;
  private int mBlockPadding;
  private int mMinTitleWidth;
  /**
   * The marks drawn beside a block's two lines: the check that says a
   * programme will record, and the badge that says it is a first showing.
   *
   * One instance each, bounded once here rather than fetched per block.  Every
   * marked block on screen draws them, and redrawRows() rebinds every visible
   * row at a time, so building them in the bind was a couple of hundred
   * Drawables an update.  Safe to share: both are plain bitmaps with no
   * per-view state, and every block gives them the same bounds.
   */
  private Drawable mCheck;
  private Drawable mBadge;
  private RowAdapter mAdapter;
  private RecyclerView mList;
  /** Set while a page is in flight, so scrolling cannot ask for it twice. */
  private boolean mLoadingRows = false;
  private boolean mLoadingSpan = false;
  /** Cleared when the box answers with a short page: there are no more. */
  private boolean mMoreRows = true;
  /** Set when a channel page was refused, to say so rather than show blank. */
  private boolean mLoadFailed = false;
  /** Set when the rows came from {@link ChannelCache}, not from the box. */
  private boolean mLineupCached = false;
  /**
   * Bumped whenever what is in flight was asked about a grid that no longer
   * exists -- another day, or another lineup.  An answer carrying an older
   * number is dropped rather than merged into the grid that replaced it.
   */
  private int mGeneration = 0;

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
    // Bounds are set rather than left at the bitmaps' own sizes: at those
    // they stretch the line they sit on and push the block's second line out
    // of it altogether.
    mCheck = ContextCompat.getDrawable(this, R.drawable.check);
    if (mCheck != null) {
      mCheck.setBounds(0, 0,
          getResources().getDimensionPixelSize(R.dimen.guide_check_size),
          getResources().getDimensionPixelSize(R.dimen.guide_check_size));
    }
    mBadge = ContextCompat.getDrawable(this, R.drawable.badge_new);
    if (mBadge != null) {
      mBadge.setBounds(0, 0,
          getResources().getDimensionPixelSize(R.dimen.guide_badge_width),
          getResources().getDimensionPixelSize(R.dimen.guide_badge_height));
    }

    Date openAt = floorToTick(new Date(startTimeExtra()));
    mDayStart = startOfDay(openAt);
    mLimitEnd = addDays(mDayStart, DAYS_AHEAD + 1);
    setWindowAround(openAt);

    mAdapter = new RowAdapter();
    mList = findViewById(R.id.guide_rows);
    mList.setLayoutManager(new LinearLayoutManager(this));
    mList.setAdapter(mAdapter);
    mList.addOnScrollListener(new RecyclerView.OnScrollListener() {
      @Override
      public void onScrolled(@NonNull RecyclerView view, int dx, int dy) {
        maybeLoadRows();
      }
    });

    SyncedHorizontalScrollView ruler = findViewById(R.id.guide_ruler_scroll);
    ruler.setSync(mSync);
    // The activity joins the sync too, not to be scrolled but so it learns
    // when an edge of the loaded hours has come into view.
    mSync.register(this);

    TextView day = findViewById(R.id.guide_day);
    day.setOnClickListener(new View.OnClickListener() {
      public void onClick(View view) {
        promptDay();
      }
    });

    buildRuler();
    mSync.setScrollX(Math.max(0, xForTime(openAt.getTime())));
    showDayAt(mSync.getScrollX());

    loadScheduled();
    startRows();
  }

  /* ---- time and geometry ---- */

  /**
   * The moment the grid should open on: "now", unless the caller named one.
   *
   * The extra is what lets the screen be pointed at a particular evening
   * rather than always at the present -- which is how the tests drive it
   * against a recorded page of listings, instead of a capture that would stop
   * matching "now" the day after it was taken.  It also fixes which day counts
   * as today, and so how far back the grid will scroll.
   */
  private long startTimeExtra() {
    Bundle extras = getIntent().getExtras();
    long when = extras == null ? 0 : extras.getLong(EXTRA_START_TIME, 0);
    return when > 0 ? when : System.currentTimeMillis();
  }

  /** The tick at or before this moment. */
  private static Date floorToTick(Date when) {
    return new Date(when.getTime() / TICK_MS * TICK_MS);
  }

  private static Date startOfDay(Date when) {
    Calendar day = Calendar.getInstance();
    day.setTime(when);
    day.set(Calendar.HOUR_OF_DAY, 0);
    day.set(Calendar.MINUTE, 0);
    day.set(Calendar.SECOND, 0);
    day.set(Calendar.MILLISECOND, 0);
    return day.getTime();
  }

  /**
   * Days are added through a Calendar, not by adding 24 hours at a time: the
   * days the clocks change are 23 and 25 hours long, and counting in hours
   * would leave the grid's far edge an hour off the day the picker named.
   */
  private static Date addDays(Date from, int days) {
    Calendar day = Calendar.getInstance();
    day.setTime(from);
    day.add(Calendar.DAY_OF_MONTH, days);
    return day.getTime();
  }

  /**
   * Put a span of loaded hours around a moment, inside the grid's bounds.
   *
   * Both bounds are midnights and the lengths are whole hours, so the window
   * stays on tick boundaries however it is clamped -- which the ruler relies
   * on to divide evenly.
   */
  private void setWindowAround(Date at) {
    long length = hoursMs(LOOK_BACK_HOURS + SPAN_STEP_HOURS);
    long start =
        floorToTick(new Date(at.getTime() - hoursMs(LOOK_BACK_HOURS)))
            .getTime();
    long end = start + length;
    if (end > mLimitEnd.getTime()) {
      // Slide the whole window back to fit under the limit.  Not a Math.min
      // against the old start: end was start + length a line ago, so once end
      // is the limit, end - length is always the earlier of the two.
      end = mLimitEnd.getTime();
      start = end - length;
    }
    if (start < mDayStart.getTime()) {
      start = mDayStart.getTime();
      end = Math.max(end, Math.min(mLimitEnd.getTime(), start + length));
    }
    mSpanStart = new Date(start);
    mSpanEnd = new Date(end);
  }

  private static long hoursMs(int hours) {
    return hours * 60L * 60L * 1000L;
  }

  private long spanMs() {
    return mSpanEnd.getTime() - mSpanStart.getTime();
  }

  /** Pixels from the left edge of the span to a moment in it. */
  private int xForTime(long when) {
    long minutes = (when - mSpanStart.getTime()) / 60000L;
    return (int) (minutes * mMinuteWidth);
  }

  private int spanWidth() {
    return (int) (spanMs() / 60000L) * mMinuteWidth;
  }

  /** The moment at a horizontal position: what the left edge is showing. */
  private Date timeAtScrollX(int scrollX) {
    if (mMinuteWidth == 0) {
      return mSpanStart;
    }
    return new Date(mSpanStart.getTime() + (scrollX / mMinuteWidth) * 60000L);
  }

  private int msToPixels(long ms) {
    return (int) (ms / 60000L) * mMinuteWidth;
  }

  private long pixelsToMs(int pixels) {
    if (mMinuteWidth == 0) {
      return 0;
    }
    return (pixels / mMinuteWidth) * 60000L;
  }

  /** How wide the scrolling part of the grid is, in pixels. */
  private int contentWidth() {
    View content = findViewById(R.id.guide_rows);
    return content == null ? 0 : content.getWidth();
  }

  /**
   * Built once.  formatClock runs for every program drawn, and a fresh
   * SimpleDateFormat per block was a real share of the cost of a redraw.
   * Safe to share: all of this runs on the main thread.
   */
  private static final SimpleDateFormat CLOCK =
      new SimpleDateFormat("h:mm a", Locale.US);
  /** The header beside the ruler: two short lines in a narrow column. */
  private static final SimpleDateFormat DAY =
      new SimpleDateFormat("EEE\nM/d", Locale.US);
  /** The same day on one line, for the picker and for screen readers. */
  private static final SimpleDateFormat DAY_LINE =
      new SimpleDateFormat("EEE M/d", Locale.US);

  private static String formatClock(Date when) {
    CLOCK.setTimeZone(TimeZone.getDefault());
    return CLOCK.format(when);
  }

  private static String formatDay(Date when) {
    DAY.setTimeZone(TimeZone.getDefault());
    return DAY.format(when);
  }

  private static String formatDayLine(Date when) {
    DAY_LINE.setTimeZone(TimeZone.getDefault());
    return DAY_LINE.format(when);
  }

  /* ---- loading ---- */

  /** The body id the channel cache is keyed by, or null before there is one. */
  private static String tsn() {
    return MindRpc.mTivoDevice == null ? null : MindRpc.mTivoDevice.tsn;
  }

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
            int next = offset + ScheduledOfferSearch.PAGE_SIZE;
            boolean full = recordings.size() >= ScheduledOfferSearch.PAGE_SIZE;
            if (full && next < MAX_SCHEDULED_PAGES
                * ScheduledOfferSearch.PAGE_SIZE) {
              requestScheduledPage(next);
            } else if (mAdapter != null) {
              // The marks are drawn from this map, so every row already on
              // screen has to be given the chance to redraw.
              redrawRows();
            }
          }
        });
  }

  /**
   * Start the grid off: from the lineup this TiVo already has, if there is
   * one, and otherwise by discovering it a page of channels at a time.
   */
  private void startRows() {
    List<JsonNode> cached = ChannelCache.get(this, tsn());
    if (cached == null) {
      loadNextRowPage();
      return;
    }
    for (int i = 0; i < cached.size(); i++) {
      mRows.add(new Row(cached.get(i)));
    }
    mLineupCached = true;
    mMoreRows = false;
    mAdapter.notifyItemRangeInserted(0, mRows.size());
    // Nothing has been laid out yet, so the list cannot say which rows are in
    // view: ask for the first page now, and ask again once it can answer.
    maybeLoadRows();
    mList.post(new Runnable() {
      public void run() {
        maybeLoadRows();
      }
    });
  }

  /**
   * Ask for whatever the rows near the viewport are missing.
   *
   * Two quite different gaps, and both can be open at once.  A row whose
   * listings do not reach across the loaded span needs them -- because the
   * lineup came from the cache and this row has never been read, or because
   * the span moved while it was being read.  And, while the lineup is still
   * being discovered, the end of the rows coming into view means there are
   * more channels to ask for.
   */
  private void maybeLoadRows() {
    if (mList == null || mAdapter == null) {
      return;
    }
    LinearLayoutManager layout = (LinearLayoutManager) mList.getLayoutManager();
    if (layout == null) {
      return;
    }
    int last = layout.findLastVisibleItemPosition();
    int through = (last == RecyclerView.NO_POSITION ? 0 : last) + ROW_PREFETCH;

    // Not while the span is moving: its own fetch covers these rows, and the
    // two would ask the box for the same listings twice.
    if (!mLoadingSpan) {
      for (int i = 0; i <= through && i < mRows.size(); i++) {
        Row row = mRows.get(i);
        if (row.loading || row.covers(mSpanStart, mSpanEnd)) {
          continue;
        }
        loadRowsFrom(i);
        // That request covers a page of rows from here, so skip past them.
        i += GridRowSearch.PAGE_SIZE - 1;
      }
    }

    if (!mLineupCached && !mLoadingRows && mMoreRows
        && last >= mRows.size() - ROW_PREFETCH) {
      loadNextRowPage();
    }
  }

  /**
   * Fetch the loaded span for a page of rows that already exist.
   *
   * Anchored ON the first of them, not on the row before: the anchor is
   * inclusive, so a page covers exactly the {@link GridRowSearch#PAGE_SIZE}
   * rows from there and nothing is skipped.  (Discovering the lineup uses the
   * other convention -- anchor the last row already held and drop the repeat
   * -- and borrowing it here quietly costs one channel per page.)
   */
  private void loadRowsFrom(final int index) {
    final int generation = mGeneration;
    final Date from = mSpanStart;
    final Date to = mSpanEnd;
    final int count = Math.min(GridRowSearch.PAGE_SIZE, mRows.size() - index);
    for (int i = index; i < index + count; i++) {
      mRows.get(i).loading = true;
    }
    final Object token = new Object();
    Utils.showProgress(this, token, true);
    MindRpc.addRequest(new GridRowSearch(mRows.get(index).channel, from, to),
        new MindRpcResponseListener() {
          public void onResponse(MindRpcResponse response) {
            Utils.showProgress(Guide.this, token, false);
            if (generation != mGeneration) {
              return;
            }
            for (int i = index; i < index + count && i < mRows.size(); i++) {
              mRows.get(i).loading = false;
            }
            if (Utils.isError(response)) {
              // The rows stay marked as holding no listings, so scrolling back
              // past them asks again -- but say so meanwhile.  With a cached
              // lineup the channel column is already drawn, so a refusal
              // otherwise reads as a box with nothing on at all.
              Utils.log("Guide: listings page failed: "
                  + Utils.errorText(response));
              mLoadFailed = true;
              showEmptyIfNothing();
              return;
            }
            JsonNode gridRows = response.getBody().path("gridRow");
            if (mLineupCached && !lineupStillMatches(index, count, gridRows)) {
              rediscoverLineup();
              return;
            }
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
              row.note(from, to);
            }
            // And every row this page was anchored across, named in the
            // answer or not.  The box does answer with a row per channel even
            // where it has no listings -- checked against a real one past the
            // end of its guide data -- so this only bites when something goes
            // oddly wrong.  When it does, the window reads as empty for these
            // rows until the span moves, which is far better than the
            // alternative: a row left uncovered is a row maybeLoadRows() asks
            // about again the moment this answer lands, for ever.
            for (int i = index; i < index + count && i < mRows.size(); i++) {
              mRows.get(i).note(from, to);
            }
            mLoadFailed = false;
            showEmptyIfNothing();
            redrawRows();
            maybeLoadRows();
            // A wide screen can want more hours than the opening span holds,
            // and with a cached lineup this is the only place that finds out:
            // loadNextRowPage, which asks the same question on the cold path,
            // is never called at all.
            maybeLoadSpan(mSync.getScrollX());
          }
        });
  }

  /**
   * Does a page of listings still describe the channels that were cached for
   * those rows?
   *
   * Forgiving in one direction on purpose.  The box answers with a row per
   * channel whether or not it has listings for the window -- checked against a
   * real box past the end of its guide data -- so a page ought to name exactly
   * the rows it was anchored across.  But a page naming *fewer* is let pass,
   * because a truncated answer looks the same and throwing the lineup away
   * over one costs a full re-read.  A channel the cache does not know, or more
   * rows than were asked about, is a real change: anything added or dropped
   * shifts every page after it, so the next page catches what this one let
   * pass.
   */
  private boolean lineupStillMatches(int index, int count, JsonNode gridRows) {
    if (gridRows.size() > count) {
      return false;
    }
    for (int i = 0; i < gridRows.size(); i++) {
      if (indexOfChannel(gridRows.path(i).path("channel"), index,
          index + count) < 0) {
        return false;
      }
    }
    return true;
  }

  /**
   * The lineup is not what was cached: throw it away and read it afresh.
   *
   * The grid empties rather than trying to patch itself up.  A lineup that has
   * changed has moved every row after the change, and reading it again is a
   * handful of requests -- the same handful this screen used to pay on every
   * single open.
   */
  private void rediscoverLineup() {
    Utils.log("Guide: the lineup has changed; reading it again");
    ChannelCache.clear(this, tsn());
    mGeneration++;
    int had = mRows.size();
    mRows.clear();
    if (had > 0) {
      mAdapter.notifyItemRangeRemoved(0, had);
    }
    mLineupCached = false;
    mMoreRows = true;
    mLoadingRows = false;
    mLoadingSpan = false;
    mLoadFailed = false;
    loadNextRowPage();
  }

  /** Ask for the next block of channels, anchored after the last one loaded. */
  private void loadNextRowPage() {
    if (mLoadingRows || !mMoreRows) {
      return;
    }
    mLoadingRows = true;
    final int generation = mGeneration;
    final Object token = new Object();
    Utils.showProgress(this, token, true);

    // The first page has no anchor, which starts it at the top of the lineup.
    final boolean first = mRows.isEmpty();
    JsonNode anchor = first ? null : mRows.get(mRows.size() - 1).channel;
    // The span can move while this is in flight, and these rows would then
    // hold listings for a window that is no longer the one on screen.  They
    // are marked with the window they were actually read for, so
    // maybeLoadRows() sees the shortfall and asks for the rest.
    final Date from = mSpanStart;
    final Date to = mSpanEnd;

    MindRpc.addRequest(new GridRowSearch(anchor, from, to),
        new MindRpcResponseListener() {
          public void onResponse(MindRpcResponse response) {
            Utils.showProgress(Guide.this, token, false);
            if (generation != mGeneration) {
              return;
            }
            mLoadingRows = false;
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
              row.note(from, to);
              mRows.add(row);
              added++;
            }

            // The end of the lineup is a page the box could not fill, not
            // merely one this screen took nothing from: an anchor-only page
            // can also happen when the lineup shifts underneath, and treating
            // that as the end would stop channel paging for good.
            if (gridRows.size() < GridRowSearch.PAGE_SIZE) {
              mMoreRows = false;
              // The whole lineup is now known, so it is worth keeping.
              saveLineup();
            }
            mLoadFailed = false;
            if (first) {
              buildRuler();
            }
            if (added > 0) {
              mAdapter.notifyItemRangeInserted(mRows.size() - added, added);
            }
            showEmptyIfNothing();
            // The first page may not fill a tall screen on its own, and a wide
            // one can want more hours than the opening span holds.
            maybeLoadRows();
            maybeLoadSpan(mSync.getScrollX());
          }
        });
  }

  private void saveLineup() {
    List<JsonNode> channels = new ArrayList<JsonNode>(mRows.size());
    for (int i = 0; i < mRows.size(); i++) {
      channels.add(mRows.get(i).channel);
    }
    ChannelCache.put(this, tsn(), channels);
  }

  /**
   * Rebind every row that is on screen.
   *
   * The rows themselves have not come or gone -- what changed is something
   * they all draw from: the scheduled set, or where the loaded span sits.
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

  /**
   * Fetch one stretch of time for every row that already holds listings.
   *
   * Walked in pages anchored on each page's own first row, and merged into
   * rows by channel identity rather than by position, so a lineup that shifted
   * underneath cannot put one channel's programs under another's name.
   *
   * @param onArrived Run once something came back, for the trimming that had
   *     to wait until there was something to keep.
   * @param onNothingArrived Run when every page failed, to put back whatever
   *     the caller widened the span by.
   */
  private void loadSpanDelta(final Date from, final Date to,
      final Runnable onArrived, final Runnable onNothingArrived) {
    List<Integer> anchors = new ArrayList<Integer>();
    for (int i = 0; i < mRows.size();) {
      if (mRows.get(i).loadedFrom == null) {
        i++;
        continue;
      }
      anchors.add(i);
      i += GridRowSearch.PAGE_SIZE;
    }
    if (anchors.isEmpty()) {
      mLoadingSpan = false;
      return;
    }

    final int generation = mGeneration;
    final int[] outstanding = new int[] { anchors.size() };
    final boolean[] anySucceeded = new boolean[] { false };
    for (int page = 0; page < anchors.size(); page++) {
      final Object token = new Object();
      Utils.showProgress(this, token, true);
      MindRpc.addRequest(
          new GridRowSearch(mRows.get(anchors.get(page)).channel, from, to),
          new MindRpcResponseListener() {
            public void onResponse(MindRpcResponse response) {
              Utils.showProgress(Guide.this, token, false);
              if (generation != mGeneration) {
                return;
              }
              if (Utils.isError(response)) {
                Utils.log("Guide: span page failed: "
                    + Utils.errorText(response));
              } else {
                anySucceeded[0] = true;
                JsonNode gridRows = response.getBody().path("gridRow");
                for (int i = 0; i < gridRows.size(); i++) {
                  JsonNode gridRow = gridRows.path(i);
                  Row row = rowForChannel(gridRow.path("channel"));
                  // A row holding nothing is not being extended -- it needs
                  // the whole span, which maybeLoadRows() will ask for.
                  if (row == null || row.loadedFrom == null) {
                    continue;
                  }
                  JsonNode offers = gridRow.path("offer");
                  for (int j = 0; j < offers.size(); j++) {
                    row.add(offers.path(j));
                  }
                  row.note(from, to);
                }
              }
              // The span is released once every page has reported back,
              // successfully or not -- but not until after the hooks below
              // have run.  Both of them move the grid, and moving the grid
              // asks this screen to look at its edges again; with the span
              // already released that look would start the very extend that
              // is being finished or undone here.  On the failure path that
              // is not merely wasteful but endless: the rollback re-issues
              // the refusal that caused it, for ever.
              if (--outstanding[0] == 0) {
                if (anySucceeded[0]) {
                  if (onArrived != null) {
                    onArrived.run();
                  }
                  mLoadingSpan = false;
                  // Look at the edges again rather than waiting to be
                  // scrolled.  A fling easily outruns a load: it pins the row
                  // at the end of the span while this was in flight, and a
                  // scroller sitting at its limit reports no further scrolls
                  // at all -- so nothing would ever ask for the next stretch
                  // and the grid would stop dead there.
                  maybeLoadSpan(mSync.getScrollX());
                } else {
                  if (onNothingArrived != null) {
                    onNothingArrived.run();
                  }
                  mLoadingSpan = false;
                }
                redrawRows();
                maybeLoadRows();
              }
            }
          });
    }
  }

  /**
   * Grow the span forwards by another {@link #SPAN_STEP_HOURS}, up to the last
   * day the grid goes to.
   */
  private void loadNextSpan() {
    if (mLoadingSpan || !mSpanEnd.before(mLimitEnd) || !anyRowLoaded()) {
      return;
    }
    final Date from = mSpanEnd;
    Date to = new Date(from.getTime() + hoursMs(SPAN_STEP_HOURS));
    if (to.after(mLimitEnd)) {
      to = mLimitEnd;
    }
    mLoadingSpan = true;
    mSpanEnd = to;
    // Widen the ruler and the rows straight away, so the grid does not stop
    // dead at the old edge while the new listings are in flight.
    buildRuler();
    redrawRows();
    loadSpanDelta(from, to, new Runnable() {
      public void run() {
        // Give back as much of the far side as can be spared, so a long
        // scroll forwards does not drag the whole of the day behind it along
        // too.  Only now there is something to keep: trimming before the
        // request would have thrown away hours the grid already held and then
        // had nothing to show for it if the request was refused.
        trimStart();
        buildRuler();
      }
    }, new Runnable() {
      public void run() {
        // Nothing arrived, so take the hours back rather than leave a widened
        // ruler over a band that will never be filled -- the next extend would
        // start beyond it.
        mSpanEnd = from;
        buildRuler();
      }
    });
  }

  /** Grow the span backwards, no further than the start of today. */
  private void loadPreviousSpan() {
    if (mLoadingSpan || !mSpanStart.after(mDayStart) || !anyRowLoaded()) {
      return;
    }
    final Date to = mSpanStart;
    Date from = new Date(to.getTime() - hoursMs(SPAN_STEP_HOURS));
    if (from.before(mDayStart)) {
      from = mDayStart;
    }
    final int shift = msToPixels(to.getTime() - from.getTime());
    if (shift <= 0) {
      return;
    }
    mLoadingSpan = true;
    mSpanStart = from;
    // Everything in the grid has just moved right by the hours gained, so the
    // scroll position moves with it -- otherwise the view would jump back in
    // time by exactly the stretch that was added.
    mSync.setScrollX(mSync.getScrollX() + shift);
    buildRuler();
    redrawRows();
    loadSpanDelta(from, to, new Runnable() {
      public void run() {
        trimEnd();
        buildRuler();
      }
    }, new Runnable() {
      public void run() {
        mSpanStart = to;
        mSync.setScrollX(Math.max(0, mSync.getScrollX() - shift));
        buildRuler();
      }
    });
  }

  /**
   * Give back hours from the start of the span, once more than
   * {@link #SPAN_MAX_HOURS} are loaded and they are safely behind the viewport.
   *
   * None of this shows.  The content narrows by exactly what the scroll
   * position is moved back by, so every program stays under the same pixel,
   * and the rows carry no scrollbars to give the window away.  A viewport too
   * close to the start simply keeps the extra hours: better a wider span than
   * a grid that yanks itself sideways.
   */
  private void trimStart() {
    long excess = spanMs() - hoursMs(SPAN_MAX_HOURS);
    if (excess <= 0) {
      return;
    }
    long behind = pixelsToMs(mSync.getScrollX()) - hoursMs(KEEP_LOADED_HOURS);
    long trim = Math.min(excess, behind) / TICK_MS * TICK_MS;
    if (trim <= 0) {
      return;
    }
    mSpanStart = new Date(mSpanStart.getTime() + trim);
    for (int i = 0; i < mRows.size(); i++) {
      mRows.get(i).trimTo(mSpanStart, mSpanEnd);
    }
    mSync.setScrollX(Math.max(0, mSync.getScrollX() - msToPixels(trim)));
  }

  /** The same from the far end, which moves nothing and so needs no shift. */
  private void trimEnd() {
    long excess = spanMs() - hoursMs(SPAN_MAX_HOURS);
    if (excess <= 0) {
      return;
    }
    long ahead = spanMs() - pixelsToMs(mSync.getScrollX() + contentWidth())
        - hoursMs(KEEP_LOADED_HOURS);
    long trim = Math.min(excess, ahead) / TICK_MS * TICK_MS;
    if (trim <= 0) {
      return;
    }
    mSpanEnd = new Date(mSpanEnd.getTime() - trim);
    for (int i = 0; i < mRows.size(); i++) {
      mRows.get(i).trimTo(mSpanStart, mSpanEnd);
    }
  }

  private boolean anyRowLoaded() {
    for (int i = 0; i < mRows.size(); i++) {
      if (mRows.get(i).loadedFrom != null) {
        return true;
      }
    }
    return false;
  }

  private boolean anyRowLoading() {
    for (int i = 0; i < mRows.size(); i++) {
      if (mRows.get(i).loading) {
        return true;
      }
    }
    return false;
  }

  /**
   * The loaded row for a channel, matched on the identity the lineup gave it.
   *
   * Matched by station rather than by number: a number alone is not unique
   * across sources.
   */
  private Row rowForChannel(JsonNode channel) {
    int index = indexOfChannel(channel, 0, mRows.size());
    return index < 0 ? null : mRows.get(index);
  }

  /** Where a channel sits among the rows, searching only [from, to). */
  private int indexOfChannel(JsonNode channel, int from, int to) {
    String stationId = channel.path("stationId").asText();
    String number = channel.path("channelNumber").asText();
    for (int i = Math.max(0, from); i < Math.min(to, mRows.size()); i++) {
      JsonNode mine = mRows.get(i).channel;
      if (!"".equals(stationId)) {
        if (stationId.equals(mine.path("stationId").asText())) {
          return i;
        }
      } else if (number.equals(mine.path("channelNumber").asText())) {
        return i;
      }
    }
    return -1;
  }

  private void showEmptyIfNothing() {
    TextView empty = findViewById(R.id.guide_empty);
    // A grid with rows but no listings in any of them counts as nothing too:
    // that is what a cached lineup whose listings were refused looks like, and
    // a column of channel names with blank rows beside it explains itself to
    // nobody.
    boolean quiet = !mLoadingRows && !anyRowLoading();
    boolean nothing = quiet && (mRows.isEmpty() || !anyRowLoaded());
    // A refusal and an empty lineup look identical once the grid is blank, so
    // say which it was -- there are no rows here to scroll and prompt a retry.
    empty.setText(mLoadFailed ? R.string.guide_failed : R.string.guide_empty);
    empty.setVisibility(nothing ? View.VISIBLE : View.GONE);
  }

  /* ---- the ruler ---- */

  private void buildRuler() {
    LinearLayout ruler = findViewById(R.id.guide_ruler);
    ruler.removeAllViews();
    // Rounded up, not down.  The span is normally a whole number of ticks,
    // but a clamp to midnight need not be: floorToTick counts from the epoch,
    // and local midnight is not on a half hour in the zones whose offset ends
    // in :45 (Kathmandu, Chatham, Eucla).  Rounding down there would leave the
    // ruler short of the rows by up to half an hour of pixels.
    int ticks = (int) ((spanMs() + TICK_MS - 1) / TICK_MS);
    for (int i = 0; i < ticks; i++) {
      Date at = new Date(mSpanStart.getTime() + i * TICK_MS);
      TextView label = new TextView(this);
      label.setText(formatClock(at));
      label.setTextColor(
          getResources().getColor(R.color.guide_ruler_text, getTheme()));
      label.setLayoutParams(new LinearLayout.LayoutParams(
          TICK_MINUTES * mMinuteWidth, ViewGroup.LayoutParams.WRAP_CONTENT));
      ruler.addView(label);
    }
  }

  /* ---- the day ---- */

  /**
   * Offer the days the grid will go to, and move to the one picked.
   *
   * A list rather than a calendar widget: the choice is one of eleven days,
   * which reads better as eleven lines than as a month to navigate, and it is
   * the same dialog the rest of the app asks its questions with.
   */
  private void promptDay() {
    final List<Date> days = new ArrayList<Date>();
    List<String> labels = new ArrayList<String>();
    for (int i = 0; i <= DAYS_AHEAD; i++) {
      Date day = addDays(mDayStart, i);
      days.add(day);
      if (i == 0) {
        labels.add(getString(R.string.guide_today));
      } else if (i == 1) {
        labels.add(getString(R.string.guide_tomorrow));
      } else {
        labels.add(formatDayLine(day));
      }
    }

    ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
        android.R.layout.select_dialog_item, labels);
    AlertDialog.Builder builder = new AlertDialog.Builder(this);
    builder.setTitle(R.string.guide_pick_day);
    builder.setAdapter(adapter, new DialogInterface.OnClickListener() {
      public void onClick(DialogInterface dialog, int which) {
        goToDay(days.get(which));
      }
    });
    builder.create().show();
  }

  /**
   * Move to the same time of day on another day.
   *
   * The time is carried over rather than reset to midnight, because it is what
   * you were looking at: picking Friday while reading Tuesday evening means
   * Friday evening.  Taken from the left edge of the viewport, which is the
   * time the header names.
   */
  private void goToDay(Date day) {
    Calendar shown = Calendar.getInstance();
    shown.setTime(timeAtScrollX(mSync.getScrollX()));
    Calendar target = Calendar.getInstance();
    target.setTime(day);
    target.set(Calendar.HOUR_OF_DAY, shown.get(Calendar.HOUR_OF_DAY));
    target.set(Calendar.MINUTE, shown.get(Calendar.MINUTE));
    openAt(target.getTime());
  }

  /**
   * Point the grid at a moment: a fresh span around it, with nothing loaded.
   *
   * What was read is let go rather than kept.  A jump of days has nothing in
   * common with what was on screen, and holding it would mean carrying a week
   * and a half of listings for a grid that shows six hours.  The channels
   * stay: they are the one thing a change of day does not change.
   */
  private void openAt(Date at) {
    if (at.before(mDayStart)) {
      at = mDayStart;
    }
    if (!at.before(mLimitEnd)) {
      at = new Date(mLimitEnd.getTime() - TICK_MS);
    }
    // Everything in flight was asked about the day being left behind.
    mGeneration++;
    mLoadingSpan = false;
    mLoadingRows = false;
    mLoadFailed = false;
    setWindowAround(at);
    for (int i = 0; i < mRows.size(); i++) {
      mRows.get(i).clear();
    }
    buildRuler();
    redrawRows();
    mSync.setScrollX(Math.max(0, xForTime(at.getTime())));
    showDayAt(mSync.getScrollX());
    maybeLoadRows();
  }

  /* ---- horizontal position ---- */

  /**
   * The grid scrolled sideways.  Nothing of this activity's own moves -- this
   * is where the titles are kept in view, and where it learns that an edge of
   * the loaded hours is coming up.
   */
  public void setSyncedScrollX(int scrollX) {
    keepTitlesInView(scrollX);
    showDayAt(scrollX);
    maybeLoadSpan(scrollX);
  }

  private void maybeLoadSpan(int scrollX) {
    int visible = contentWidth();
    // Several screenfuls of slack, so the next hours are in hand well before
    // the edge is reached rather than just as it is.
    if (scrollX + visible * (1 + SPAN_PREFETCH_SCREENS) >= spanWidth()) {
      loadNextSpan();
    }
    // Backwards only once the grid has actually been scrolled sideways.  At
    // rest it sits near the left edge of the span, so an armed prefetch would
    // read the earlier part of the day on every open, which nobody asked to
    // see.  Asked of the sync rather than watched for as a touch: a tap on a
    // program, or a flick down the channel list, is a touch that never moves
    // the grid sideways at all.
    if (mSync.wasScrolled()
        && scrollX <= visible * SPAN_PREFETCH_SCREENS_BACK) {
      loadPreviousSpan();
    }
  }

  /**
   * Name the day actually on screen, not the one the grid opened on.
   *
   * The span crosses midnight as it is scrolled, and the header beside the
   * ruler is the only thing saying which day the times belong to; left at the
   * opening day it would contradict them.
   */
  private void showDayAt(int scrollX) {
    TextView day = findViewById(R.id.guide_day);
    if (day == null || mMinuteWidth == 0) {
      return;
    }
    Date at = timeAtScrollX(scrollX);
    String text = getString(R.string.guide_day_label, formatDay(at));
    if (!text.contentEquals(day.getText())) {
      day.setText(text);
      day.setContentDescription(
          getString(R.string.a11y_guide_day, formatDayLine(at)));
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
      final Airing airing) {
    final JsonNode offer = airing.offer;
    // A program already running when the span starts is clipped to the left
    // edge rather than hung off it, so it still reads as "on now".
    int left = Math.max(0, xForTime(airing.start));
    int right = Math.min(spanWidth(), xForTime(airing.end));
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
    Drawable check = scheduled ? mCheck : null;
    title.setCompoundDrawables(check, null, null, null);
    title.setCompoundDrawablePadding(check == null ? 0 : mBlockGap * 2);

    TextView detail = block.findViewById(R.id.guide_offer_detail);
    detail.setText(detailFor(airing));
    // On the second line rather than the first: the first already carries the
    // check when a program is going to record, and a block can be as narrow
    // as half an hour -- 120dp -- with no room for both on one line.
    Drawable badge = isNew(offer) ? mBadge : null;
    detail.setCompoundDrawables(badge, null, null, null);
    detail.setCompoundDrawablePadding(badge == null ? 0 : mBlockGap * 2);

    block.setContentDescription(describe(airing, scheduled));
    block.setOnClickListener(new View.OnClickListener() {
      public void onClick(View view) {
        showOffer(offer);
      }
    });

    block.setOnLongClickListener(new View.OnLongClickListener() {
      public boolean onLongClick(View view) {
        promptOffer(offer);
        return true;
      }
    });

    // A block placed while the grid is already scrolled has to be offset to
    // match, or its title sits off screen until the next scroll event.
    slideTitle(block, mSync.getScrollX());
    return true;
  }

  /**
   * A first showing, badged the way My Shows badges one.
   *
   * By the same pair of fields, and for the same reason: "isNew" is asked for
   * in the response template but a real box does not send it, while "episodic"
   * and "repeat" come back on every offer.  A film or a one-off is not new,
   * it is simply on.
   */
  private static boolean isNew(JsonNode offer) {
    return offer.path("episodic").asBoolean()
        && !offer.path("repeat").asBoolean();
  }

  /** The second line of a block: the episode, or when it starts. */
  private String detailFor(Airing airing) {
    String subtitle = airing.offer.path("subtitle").asText();
    if (!"".equals(subtitle)) {
      return subtitle;
    }
    return formatClock(new Date(airing.start));
  }

  private String describe(Airing airing, boolean scheduled) {
    JsonNode offer = airing.offer;
    StringBuilder out = new StringBuilder(offer.path("title").asText());
    String subtitle = offer.path("subtitle").asText();
    if (!"".equals(subtitle)) {
      out.append(", ").append(subtitle);
    }
    out.append(", ").append(getString(R.string.a11y_guide_time,
        formatClock(new Date(airing.start)),
        formatClock(new Date(airing.end))));
    if (isNew(offer)) {
      out.append(", ").append(getString(R.string.a11y_badge_new));
    }
    if (scheduled) {
      out.append(", ").append(getString(R.string.a11y_scheduled));
    }
    return out.toString();
  }

  /* ---- what a block offers ---- */

  /** Everything the box knows about a program: what a tap opens. */
  private void showOffer(JsonNode offer) {
    Intent intent = new Intent(Guide.this, ExploreTabs.class);
    intent.putExtra("contentId", offer.path("contentId").asText());
    intent.putExtra("collectionId", offer.path("collectionId").asText());
    intent.putExtra("offerId", offer.path("offerId").asText());
    mRefreshLauncher.launch(intent);
  }

  /**
   * The long press menu for a program.
   *
   * Recording comes first because it is what the press is nearly always for,
   * but the details a tap opens are offered here too: the two gestures are
   * easy to confuse on a grid of small blocks, and a long press that can only
   * record is a trap when all you wanted was to read the description.
   */
  private void promptOffer(final JsonNode offer) {
    final String offerId = offer.path("offerId").asText();
    final String recordingId = mScheduled.get(offerId);
    final boolean scheduled = recordingId != null;

    final ArrayList<String> choices = new ArrayList<String>();
    choices.add(getString(scheduled ? R.string.dont_record : R.string.record));
    choices.add(getString(R.string.guide_show_info));

    ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
        android.R.layout.select_dialog_item, choices);
    DialogInterface.OnClickListener onClick =
        new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int which) {
            if (which == 1) {
              showOffer(offer);
            } else if (scheduled) {
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
    rollDayForward();
  }

  /**
   * Move "today" on when the screen has been left open across midnight.
   *
   * The bounds are worked out once, in onCreate, from the moment the grid
   * opens on -- and a guide is exactly the sort of screen that gets left up
   * all evening.  Past midnight the picker would still offer yesterday as
   * "Today", and the grid would still scroll back into a day that is over.
   *
   * Only when the caller named no moment: one that did means this screen was
   * pointed at a particular day on purpose, and that day is its today.
   */
  private void rollDayForward() {
    Bundle extras = getIntent().getExtras();
    if (mDayStart == null
        || (extras != null && extras.getLong(EXTRA_START_TIME, 0) > 0)) {
      return;
    }
    Date today = startOfDay(new Date());
    if (!today.after(mDayStart)) {
      return;
    }
    mDayStart = today;
    mLimitEnd = addDays(today, DAYS_AHEAD + 1);
    // The span is left where it is.  It may now start before the day does,
    // which reads correctly -- those hours are loaded and still worth showing
    // -- and loadPreviousSpan simply will not reach any further back.
    showDayAt(mSync.getScrollX());
  }
}
