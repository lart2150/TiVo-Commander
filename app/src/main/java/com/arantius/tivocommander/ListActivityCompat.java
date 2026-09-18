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

import android.view.View;
import android.widget.ListAdapter;
import android.widget.ListView;

/**
 * The slice of android.app.ListActivity this app actually used, on top of
 * {@link BaseActivity}.
 *
 * ListActivity is a framework Activity, so screens built on it could not have
 * an action bar under an AppCompat theme.  This keeps the same contract --
 * a content view carrying a ListView at android.R.id.list, optionally with an
 * empty view at android.R.id.empty -- so the screens themselves did not have to
 * change beyond which class they extend.
 */
public class ListActivityCompat extends BaseActivity {
  private ListAdapter mAdapter;
  private ListView mList;

  public ListAdapter getListAdapter() {
    return mAdapter;
  }

  public ListView getListView() {
    if (mList == null) {
      throw new IllegalStateException(
          "getListView() before setContentView(); the content view must hold a "
              + "ListView with the id android.R.id.list");
    }
    return mList;
  }

  /**
   * Called by setContentView().  Like ListActivity, this is where the list and
   * its empty view are picked up, and where an adapter set before the content
   * view existed gets applied.
   */
  @Override
  public void onContentChanged() {
    super.onContentChanged();

    mList = findViewById(android.R.id.list);
    if (mList == null) {
      // Not every content view this activity sets is a list (several screens
      // swap in a "no results" layout), so this is not an error on its own.
      return;
    }

    View emptyView = findViewById(android.R.id.empty);
    if (emptyView != null) {
      mList.setEmptyView(emptyView);
    }
    if (mAdapter != null) {
      mList.setAdapter(mAdapter);
    }
  }

  public void setListAdapter(ListAdapter adapter) {
    mAdapter = adapter;
    if (mList != null) {
      mList.setAdapter(adapter);
    }
  }
}
