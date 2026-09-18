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

import android.app.Activity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.LayoutRes;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

/**
 * Base for the pages hosted by {@link ExploreTabs}.
 *
 * These were child Activities of a TabActivity until they were converted to
 * fragments; the helpers here stand in for the Activity methods they used, so
 * that the page bodies could stay as they were.  Each page starts as an empty
 * container and swaps in its real layout once its data arrives, which is what
 * the repeated setContentView() calls used to do.
 */
abstract public class ExploreTabFragment extends Fragment {
  private FrameLayout mContainer;

  @Override
  public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup parent,
      @Nullable Bundle savedInstanceState) {
    mContainer = new FrameLayout(inflater.getContext());
    mContainer.setLayoutParams(new ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT));
    return mContainer;
  }

  @Override
  public void onDestroyView() {
    // Give up any claim on the host's progress bar first.  A page whose
    // request is still in flight when the pager throws its view away would
    // otherwise leave the bar up for good; onViewCreated() asks again, along
    // with the request, if the page comes back.
    showProgress(false);
    super.onDestroyView();
    mContainer = null;
  }

  /** Stands in for Activity.setContentView(). */
  protected void setContent(@LayoutRes int layoutId) {
    if (mContainer == null) {
      // The response outlived the view; nothing to draw into.
      return;
    }
    mContainer.removeAllViews();
    LayoutInflater.from(mContainer.getContext())
        .inflate(layoutId, mContainer, true);
  }

  /** Stands in for Activity.findViewById(); null once the view is gone. */
  @Nullable
  protected <T extends View> T findViewById(int id) {
    View root = getView();
    return root == null ? null : root.<T>findViewById(id);
  }

  /**
   * Progress lives on the host activity; a no-op once we are detached.
   *
   * Each page passes itself as the owner of the request, so that the bar stays
   * up until every page that wants it is done, rather than until the first one
   * finishes.
   */
  protected void showProgress(boolean show) {
    Activity activity = getActivity();
    if (activity != null) {
      Utils.showProgress(activity, this, show);
    }
  }

  /** True while this fragment still has a view and an activity to talk to. */
  protected boolean isUsable() {
    return mContainer != null && getActivity() != null && isAdded();
  }
}
