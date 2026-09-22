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

package com.arantius.tivocommander.views;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.AttributeSet;
import android.widget.HorizontalScrollView;

/**
 * One row of the guide grid, or its time ruler: a horizontal scroller that
 * keeps step with every other one through a {@link GuideScrollSync}.
 */
public class SyncedHorizontalScrollView extends HorizontalScrollView
    implements GuideScrollSync.Member {
  private GuideScrollSync mSync;
  /**
   * Where the group is.
   *
   * Kept rather than just handed to scrollTo(), because scrollTo() clamps to
   * what the content can currently hold: a row asked to sit at 8pm before it
   * has been measured, or while its blocks are being replaced, silently lands
   * at 0.  Holding the wanted position lets {@link #onLayout} put it right
   * once the content has a real width.
   */
  private int mSyncedX = 0;
  /** Set while this view is being moved to the group's position. */
  private boolean mApplying = false;

  public SyncedHorizontalScrollView(Context context) {
    super(context);
  }

  public SyncedHorizontalScrollView(Context context, AttributeSet attrs) {
    super(context, attrs);
  }

  public SyncedHorizontalScrollView(Context context, AttributeSet attrs,
      int defStyleAttr) {
    super(context, attrs, defStyleAttr);
  }

  /**
   * Join a group of rows that scroll together.
   *
   * Safe to call again with the same sync -- rows are re-bound as the list
   * recycles them, and registering is what re-aligns a recycled row.
   */
  public void setSync(GuideScrollSync sync) {
    if (mSync != null && mSync != sync) {
      mSync.unregister(this);
    }
    mSync = sync;
    if (sync != null) {
      sync.register(this);
    }
  }

  public void setSyncedScrollX(int scrollX) {
    mSyncedX = scrollX;
    applySyncedScrollX();
  }

  /**
   * The position this scroller is meant to be at.
   *
   * Not always the same as getScrollX(): between a rebind and the layout that
   * follows it, the framework has clamped the real position to something the
   * half-built content can hold, and this is what it will be put back to.
   */
  public int getSyncedScrollX() {
    return mSyncedX;
  }

  private void applySyncedScrollX() {
    if (getScrollX() == mSyncedX) {
      return;
    }
    mApplying = true;
    try {
      // Not smoothScrollTo(): these are following, not animating, and a
      // smooth scroll here would lag a finger drag by its animation duration.
      scrollTo(mSyncedX, 0);
    } finally {
      mApplying = false;
    }
  }

  @Override
  protected void onLayout(boolean changed, int l, int t, int r, int b) {
    super.onLayout(changed, l, t, r, b);
    // Now the content has its real width, so a position that was clamped
    // before it did can finally be honoured.
    applySyncedScrollX();
  }

  /** The furthest right this view can currently be scrolled. */
  private int maxScrollX() {
    if (getChildCount() == 0) {
      return 0;
    }
    int viewport = getWidth() - getPaddingLeft() - getPaddingRight();
    return Math.max(0, getChildAt(0).getWidth() - viewport);
  }

  /**
   * Was this scroll the framework hauling us back, rather than the user?
   *
   * A scroll that lands exactly at the furthest this content can go, while
   * the group is further right than that, is a clamp: the content is not wide
   * enough to hold the position, so the framework picked the nearest it could.
   * That happens whenever the content is replaced or remeasured -- a row being
   * re-bound, a row re-attached without a bind, the span growing, a rotation
   * -- and in every one of those cases relaying it would drag the whole grid
   * back to wherever the narrowest row happened to stop.
   *
   * A real drag fails this test: the user cannot scroll past maxScrollX, and
   * anything short of it is not equal to it.
   */
  private boolean isClamp(int scrollX) {
    return scrollX < mSyncedX && scrollX == maxScrollX();
  }

  @Override
  protected void onScrollChanged(int l, int t, int oldl, int oldt) {
    super.onScrollChanged(l, t, oldl, oldt);
    if (mApplying || isClamp(l)) {
      // Us moving to the group's position, or the content being too narrow to
      // hold it.  Neither is the user scrolling.
      return;
    }
    // While the sync is pushing a position out, this scroll *is* that push
    // arriving; reporting it back would bounce it around the group forever.
    if (mSync != null && !mSync.isBroadcasting()) {
      mSyncedX = l;
      mSync.onMemberScrolled(this, l);
    }
  }

  /**
   * No performClick() to go with this, and none is wanted: the override only
   * watches for the start of a gesture and hands every event to super, so this
   * view never consumes a click of its own.  The things that are clickable are
   * the program blocks inside it, which carry their own descriptions.
   */
  @SuppressLint("ClickableViewAccessibility")
  @Override
  public boolean onTouchEvent(android.view.MotionEvent event) {
    if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN
        && mSync != null) {
      // Taking over from whatever was moving.  A fling left running on
      // another row would keep reporting positions and fight this drag.
      mSync.onMemberTouched(this);
    }
    return super.onTouchEvent(event);
  }

  /**
   * Stop a fling still in flight.
   *
   * There is no public abort on HorizontalScrollView, but starting a fling at
   * zero velocity ends the one already running, which is the same thing.
   */
  public void stopFollowing() {
    fling(0);
  }

  /**
   * Rejoin the group.
   *
   * A row is not necessarily re-bound when the list brings it back: the view
   * can be detached and re-attached during a layout pass with no call to
   * onBindViewHolder.  Registering here rather than only in the binding is
   * what stops such a row going deaf and sitting still while the rest scroll.
   */
  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    if (mSync != null) {
      mSync.register(this);
    }
  }

  @Override
  protected void onDetachedFromWindow() {
    super.onDetachedFromWindow();
    if (mSync != null) {
      mSync.unregister(this);
    }
  }
}
