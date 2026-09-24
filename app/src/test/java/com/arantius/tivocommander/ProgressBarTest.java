/*
DVR Commander allows control of a TiVo Premiere device.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;

import android.app.Activity;
import android.view.View;
import android.widget.ProgressBar;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;

/** The one progress bar a screen shares between everything loading into it. */
@RunWith(RobolectricTestRunner.class)
public class ProgressBarTest {
  /** Stands in for a fragment or a listener -- anything that can want the bar. */
  private static final Object OWNER_A = new Object();
  private static final Object OWNER_B = new Object();

  private ActivityController<About> mController;
  private Activity mActivity;

  @Before
  public void setUp() {
    // About is the simplest BaseActivity: a layout and nothing else.
    mController = Robolectric.buildActivity(About.class).setup();
    mActivity = mController.get();
  }

  @After
  public void tearDown() {
    mController.close();
  }

  private ProgressBar bar() {
    return mActivity.findViewById(R.id.global_progress);
  }

  private int visibility() {
    ProgressBar bar = bar();
    assertNotNull("the container layout should always hold a bar", bar);
    return bar.getVisibility();
  }

  @Test
  public void theBarStartsHidden() {
    assertEquals(View.GONE, visibility());
  }

  @Test
  public void oneOwnerShowsAndHidesIt() {
    Utils.showProgress(mActivity, OWNER_A, true);
    assertEquals(View.VISIBLE, visibility());

    Utils.showProgress(mActivity, OWNER_A, false);
    assertEquals(View.GONE, visibility());
  }

  @Test
  public void theBarStaysUpWhileAnyOwnerStillWantsIt() {
    // ExploreTabs has three pages loading into one bar; the first to finish
    // used to hide it while the others were still going.
    Utils.showProgress(mActivity, OWNER_A, true);
    Utils.showProgress(mActivity, OWNER_B, true);

    Utils.showProgress(mActivity, OWNER_A, false);
    assertEquals("B is still waiting", View.VISIBLE, visibility());

    Utils.showProgress(mActivity, OWNER_B, false);
    assertEquals(View.GONE, visibility());
  }

  @Test
  public void oneOwnerAskingTwiceOnlyCountsOnce() {
    Utils.showProgress(mActivity, OWNER_A, true);
    Utils.showProgress(mActivity, OWNER_A, true);
    Utils.showProgress(mActivity, OWNER_A, false);
    assertEquals(View.GONE, visibility());
  }

  @Test
  public void hidingSomethingThatNeverAskedChangesNothing() {
    Utils.showProgress(mActivity, OWNER_A, true);
    Utils.showProgress(mActivity, OWNER_B, false);
    assertEquals(View.VISIBLE, visibility());
  }

  @Test
  public void theActivityIsItsOwnOwnerInTheTwoArgumentForm() {
    // The screens that drive the bar directly go through this one.
    Utils.showProgress(mActivity, true);
    assertEquals(View.VISIBLE, visibility());
    Utils.showProgress(mActivity, false);
    assertEquals(View.GONE, visibility());
  }

  @Test
  public void anActivityOwnerDoesNotCollideWithAFragmentOwner() {
    Utils.showProgress(mActivity, true);
    Utils.showProgress(mActivity, OWNER_A, true);

    Utils.showProgress(mActivity, false);
    assertEquals("the other owner is still waiting", View.VISIBLE,
        visibility());

    Utils.showProgress(mActivity, OWNER_A, false);
    assertEquals(View.GONE, visibility());
  }

  @Test
  public void swappingTheContentDoesNotTakeTheBarWithIt() {
    // setContent() empties the container on every layout swap, and the bar
    // used to be inside it -- so a screen that swapped in its real layout
    // while still loading lost the bar, and whatever it was showing.
    Utils.showProgress(mActivity, OWNER_A, true);
    assertEquals(View.VISIBLE, visibility());

    ((About) mActivity).setContent(R.layout.no_results);

    assertNotNull("the bar should have survived", bar());
    assertEquals("and should still be showing", View.VISIBLE, visibility());

    Utils.showProgress(mActivity, OWNER_A, false);
    assertEquals(View.GONE, visibility());
  }

  @Test
  public void theBarSitsOverTheContentRatherThanInsideIt() {
    // Inside the swappable container it would be removed by setContent(); as
    // a later sibling it draws on top of whatever the screen put up.
    View content = mActivity.findViewById(R.id.activity_content);
    assertNotNull(content);
    assertNotSame("the bar must not live inside the swappable container",
        content, bar().getParent());
  }
}
