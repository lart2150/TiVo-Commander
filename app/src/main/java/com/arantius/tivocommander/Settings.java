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

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Menu;

import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import com.arantius.tivocommander.rpc.MindRpc;

/**
 * This was a PreferenceActivity, which is a framework Activity and so could not
 * have an action bar under an AppCompat theme.  androidx.preference reads and
 * writes the same default SharedPreferences file, so existing settings carry
 * over untouched.
 */
public class Settings extends BaseActivity {
  public static class SettingsFragment extends PreferenceFragmentCompat {
    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
      setPreferencesFromResource(R.xml.preferences, rootKey);
    }
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    MindRpc.disconnect();
    setContent(R.layout.settings);
    if (savedInstanceState == null) {
      getSupportFragmentManager().beginTransaction()
          .replace(R.id.settings_container, new SettingsFragment())
          .commit();
    }
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    Utils.createShortOptionsMenu(menu, this);
    return true;
  }

  @Override
  protected void onPause() {
    super.onPause();
    Utils.log("Activity:Pause:Settings");

    SharedPreferences prefs =
        PreferenceManager.getDefaultSharedPreferences(getBaseContext());

    Utils.DEBUG_LOG = prefs.getBoolean("debug_log", false);
  }

  @Override
  protected void onResume() {
    super.onResume();
    setTitle("Settings");
    Utils.log("Activity:Resume:Settings");
  }
}
