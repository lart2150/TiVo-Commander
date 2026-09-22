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
  private boolean mScrolled = false;

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

  /**
   * Has a member ever reported a position of its own, rather than been told
   * one?
   *
   * In other words, has the grid actually been scrolled sideways.  A touch is
   * not the same thing and does not count: a tap on a program, or a flick
   * down the channel list, starts a gesture that never moves the grid at all.
   */
  public boolean wasScrolled() {
    return mScrolled;
  }

  /**
   * Move the whole group, the member that last reported included.
   *
   * Not a member telling the others where it went: this is the grid itself
   * being repositioned, which happens when the loaded span gains or loses
   * hours at its start.  Everything in the rows has shifted sideways by the
   * same amount, so every scroller has to be offset to keep the same moment
   * under the same pixel.
   *
   * Pushed out under the broadcast guard like any other position, so a
   * scroller that cannot take it yet -- its content has not been re-measured
   * to the new width -- clamps quietly and is put right on its next layout,
   * rather than dragging the group back to whatever it clamped to.
   */
  public void setScrollX(int scrollX) {
    if (scrollX < 0) {
      scrollX = 0;
    }
    if (scrollX == mScrollX) {
      return;
    }
    // Anything still coasting is coasting towards a position worked out in
    // the old layout, and its next frame would overwrite the one being set
    // here and drag the whole group along with it.  Stopped before mScrollX
    // moves, so that the stop's own scroll report reads as the position the
    // group is already at and is ignored.
    List<Member> stopping = new ArrayList<Member>(mMembers);
    for (int i = 0; i < stopping.size(); i++) {
      stopping.get(i).stopFollowing();
    }
    mScrollX = scrollX;
    boolean wasBroadcasting = mBroadcasting;
    mBroadcasting = true;
    try {
      // Over a copy: telling a member where to sit can drive a layout that
      // detaches a row, and that row unregisters from inside this loop.
      // Indexing the live list then skips whoever slid down into the vacated
      // slot, leaving one row parked at the old hour until the next
      // broadcast.  The bound re-reads mScrollX because a member can also
      // move the group from inside being told where to sit -- the guide
      // widens its span that way -- and that call has already reached
      // everyone with the newer position.
      List<Member> members = new ArrayList<Member>(mMembers);
      for (int i = 0; i < members.size() && mScrollX == scrollX; i++) {
        members.get(i).setSyncedScrollX(scrollX);
      }
    } finally {
      mBroadcasting = wasBroadcasting;
    }
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
    List<Member> members = new ArrayList<Member>(mMembers);
    for (int i = 0; i < members.size(); i++) {
      Member member = members.get(i);
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
    mScrolled = true;
    mScrollX = scrollX;
    boolean wasBroadcasting = mBroadcasting;
    mBroadcasting = true;
    try {
      // Over a copy, for the reason setScrollX takes one: a member can join
      // or leave while being caught up, and indexing the live list past a
      // removal skips whoever moved down into the gap.  mScrollX is re-read
      // because a member can move the group from inside this call.
      List<Member> members = new ArrayList<Member>(mMembers);
      for (int i = 0; i < members.size() && mScrollX == scrollX; i++) {
        Member member = members.get(i);
        if (member != source) {
          member.setSyncedScrollX(scrollX);
        }
      }
    } finally {
      mBroadcasting = wasBroadcasting;
    }
  }
}
