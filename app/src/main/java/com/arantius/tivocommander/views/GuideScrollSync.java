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

import java.util.ArrayList;
import java.util.List;

/**
 * The one horizontal position every row of the guide is scrolled to.
 *
 * The grid is a vertical list of independently scrolling rows rather than one
 * wide surface, because that is what lets the channel column stay put and the
 * list recycle its rows.  The cost is that the rows would drift apart, so they
 * all share this: whichever one the finger is on reports here, and the rest
 * follow.
 *
 * A row that scrolls because it was told to must not turn around and tell
 * everyone again -- that is an endless round trip, and on a fling it fights
 * the scroller.  {@link #isBroadcasting()} is how a member knows to keep quiet.
 */
public class GuideScrollSync {
  /** A row that can be told where to sit. */
  public interface Member {
    void setSyncedScrollX(int scrollX);

    /**
     * Give up driving: someone else has taken hold of the grid.
     *
     * Only the rows that can fling need to do anything about this.
     */
    default void stopFollowing() {
    }
  }

  private final List<Member> mMembers = new ArrayList<Member>();
  private int mScrollX = 0;
  private boolean mBroadcasting = false;

  /**
   * Join, and immediately catch up to where everyone else is.
   *
   * The catch-up is the point: a recycled row is bound while the list is
   * scrolled somewhere off to the right, and without this it would appear
   * showing midnight while its neighbours show mid-evening.
   */
  public void register(Member member) {
    if (!mMembers.contains(member)) {
      mMembers.add(member);
    }
    // Guarded like a broadcast: catching one row up is not that row telling
    // the group where to go.  Unguarded, a row that cannot take the position
    // yet -- because it has not been measured -- reports the zero it clamped
    // to, and drags everybody else back to the start of the span with it.
    // Saved and restored rather than cleared: this can run *inside* a
    // broadcast -- catching a row up can make the list attach another row,
    // which registers -- and clearing the flag would re-open the guard for
    // the rest of the outer loop.
    boolean wasBroadcasting = mBroadcasting;
    mBroadcasting = true;
    try {
      member.setSyncedScrollX(mScrollX);
    } finally {
      mBroadcasting = wasBroadcasting;
    }
  }

  public void unregister(Member member) {
    mMembers.remove(member);
  }

  /** Where the grid is scrolled to, in pixels from the start of the span. */
  public int getScrollX() {
    return mScrollX;
  }

  /** True while {@link #onMemberScrolled} is pushing a position around. */
  public boolean isBroadcasting() {
    return mBroadcasting;
  }

  /**
   * A finger landed on one row.  Whatever else was still coasting has to stop,
   * or two scrollers end up reporting different positions at once and the grid
   * jitters between them.
   */
  public void onMemberTouched(Member source) {
    for (int i = 0; i < mMembers.size(); i++) {
      Member member = mMembers.get(i);
      if (member != source) {
        member.stopFollowing();
      }
    }
  }

  /** One row moved under the finger; bring the others along. */
  public void onMemberScrolled(Member source, int scrollX) {
    if (mBroadcasting || scrollX == mScrollX) {
      return;
    }
    mScrollX = scrollX;
    boolean wasBroadcasting = mBroadcasting;
    mBroadcasting = true;
    try {
      // Indexed, and re-reading size each time: a member can join or leave
      // while being caught up, so the list is not stable across the loop.
      for (int i = 0; i < mMembers.size(); i++) {
        Member member = mMembers.get(i);
        if (member != source) {
          member.setSyncedScrollX(scrollX);
        }
      }
    } finally {
      mBroadcasting = wasBroadcasting;
    }
  }
}
