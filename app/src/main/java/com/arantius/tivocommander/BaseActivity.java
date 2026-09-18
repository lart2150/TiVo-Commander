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

import android.os.Bundle;
import android.view.MenuItem;
import android.view.ViewGroup;

import androidx.annotation.LayoutRes;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Common base for every screen in the app.
 *
 * The app themes are AppCompat themes, which switch the framework's own action
 * bar off on the assumption that AppCompatDelegate will supply one instead --
 * and that only happens for an AppCompatActivity.  While these screens were
 * plain framework Activities they therefore drew with no action bar at all: no
 * Up arrow and no overflow menu.  Extending this class is what puts the bar
 * back, and the Up arrow is turned on here so no screen can forget it.
 */
public class BaseActivity extends AppCompatActivity {
  private ViewGroup mContentContainer;

  @Override
  protected void onPostCreate(Bundle savedInstanceState) {
    super.onPostCreate(savedInstanceState);
    Utils.activateHomeButton(this);
  }

  /**
   * Swap in a layout, for screens that change their content once their data
   * arrives (a real layout, or a "no results" one).
   *
   * Calling setContentView() a second time does not work here: the replacement
   * is installed after the window is attached, so it never receives the window
   * inset dispatch that android:fitsSystemWindows depends on, and it lays out
   * at the top of the window behind the status bar and action bar.  This keeps
   * one container as the content view -- installed early, so it is inset
   * correctly -- and swaps layouts inside it instead.
   */
  protected void setContent(@LayoutRes int layoutId) {
    if (mContentContainer == null) {
      setContentView(R.layout.activity_container);
      mContentContainer = findViewById(R.id.activity_content);
    }
    mContentContainer.removeAllViews();
    getLayoutInflater().inflate(layoutId, mContentContainer, true);
    // setContentView() would have raised this; the subclasses that pick their
    // list view out of the content still need it.
    onContentChanged();
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    // Screens that want something other than "up means back" override this and
    // route through Utils.onOptionsItemSelected(); this is the default for the
    // ones that do not.
    if (item.getItemId() == android.R.id.home) {
      finish();
      return true;
    }
    return super.onOptionsItemSelected(item);
  }
}
