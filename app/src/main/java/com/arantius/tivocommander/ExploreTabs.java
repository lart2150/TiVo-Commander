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

import java.util.ArrayList;
import java.util.List;

import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.arantius.tivocommander.rpc.MindRpc;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

/**
 * Hosts the Explore / Credits / Similar pages.
 *
 * This was a TabActivity hosting each page as a child Activity through
 * LocalActivityManager.  Child activities have no window of their own, so on
 * Android 13+ the back gesture reached them inconsistently: onBackPressed()
 * fired on some devices, OnBackInvokedCallback on others, neither on the rest.
 * The pages are fragments of this single activity now, so back is ordinary
 * activity back and behaves the same everywhere.
 */
public class ExploreTabs extends BaseActivity {
  /** One page of the pager. */
  private static class TabSpec {
    final String title;
    final int iconId;
    final Class<? extends Fragment> fragmentClass;

    TabSpec(String title, int iconId, Class<? extends Fragment> fragmentClass) {
      this.title = title;
      this.iconId = iconId;
      this.fragmentClass = fragmentClass;
    }
  }

  private final List<TabSpec> mTabs = new ArrayList<TabSpec>();

  private String mCollectionId;
  private String mContentId;
  private String mOfferId;
  private String mRecordingId;

  /** The ids every page needs, as fragment arguments. */
  private Bundle makeArgs() {
    Bundle args = new Bundle();
    if (mCollectionId != null) {
      args.putString("collectionId", mCollectionId);
    }
    if (mContentId != null) {
      args.putString("contentId", mContentId);
    }
    if (mOfferId != null) {
      args.putString("offerId", mOfferId);
    }
    if (mRecordingId != null) {
      args.putString("recordingId", mRecordingId);
    }
    return args;
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    Bundle bundle = getIntent().getExtras();

    // Put the URI details, if any, in the bundle. Where we'll read them
    // later, even if MindRpc.init() restarts us with only bundle data.
    Uri uri = getIntent().getData();
    if (uri != null) {
      uriToBundle(uri, bundle);
    }

    if (MindRpc.init(this, bundle)) {
      Utils.log("ExploreTabs: finishing due to MindRpc.init");
      finish();
      return;
    }

    setContent(R.layout.explore_tabs);
    setTitle("Explore");

    if (bundle != null) {
      mCollectionId = bundle.getString("collectionId");
      if ("tivo:cl.0".equals(mCollectionId)) {
        mCollectionId = null;
      }
      mContentId = bundle.getString("contentId");
      mOfferId = bundle.getString("offerId");
      mRecordingId = bundle.getString("recordingId");
    }

    mTabs.add(new TabSpec("Explore", R.drawable.icon_tv, Explore.class));
    if (mCollectionId != null) {
      mTabs.add(new TabSpec("Credits", R.drawable.icon_people, Credits.class));
      mTabs.add(
          new TabSpec("Similar", R.drawable.icon_similar, Suggestions.class));
    }

    final Bundle args = makeArgs();
    final ViewPager2 pager = findViewById(R.id.explore_pager);
    pager.setAdapter(new FragmentStateAdapter(this) {
      @NonNull
      @Override
      public Fragment createFragment(int position) {
        Fragment fragment = getSupportFragmentManager().getFragmentFactory()
            .instantiate(getClassLoader(),
                mTabs.get(position).fragmentClass.getName());
        fragment.setArguments(new Bundle(args));
        return fragment;
      }

      @Override
      public int getItemCount() {
        return mTabs.size();
      }
    });

    final TabLayout tabLayout = findViewById(R.id.explore_tab_layout);
    new TabLayoutMediator(tabLayout, pager,
        new TabLayoutMediator.TabConfigurationStrategy() {
          @Override
          public void onConfigureTab(@NonNull TabLayout.Tab tab, int position) {
            TabSpec spec = mTabs.get(position);
            View indicator = getLayoutInflater().inflate(
                R.layout.tab_indicator, tabLayout, false);
            ((TextView) indicator.findViewById(R.id.title)).setText(spec.title);
            ((ImageView) indicator.findViewById(R.id.icon))
                .setImageResource(spec.iconId);
            tab.setCustomView(indicator);
            tab.setContentDescription(spec.title);
          }
        }).attach();

    // A lone tab has nothing to switch to.
    tabLayout.setVisibility(mTabs.size() > 1 ? View.VISIBLE : View.GONE);
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    Utils.createFullOptionsMenu(menu, this);
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    return Utils.onOptionsItemSelected(item, this);
  }

  /** Parse a TiVo URL in a Uri object into an extras bundle. */
  private void uriToBundle(Uri uri, Bundle bundle) {
    final String[] keys = new String[] {
        "collectionId", "contentId", "offerId",
    };
    for (String key : keys) {
      String val = uri.getQueryParameter(key);
      if (val != null) {
        bundle.putString(key, val);
      }
    }
  }
}
