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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;

import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * The deep link into Explore.  A VIEW intent from the manifest filter carries
 * its ids in the query string and no extras at all, so this parse is the only
 * thing between the link and an Explore screen with nothing to show.
 */
@RunWith(RobolectricTestRunner.class)
public class ExploreTabsUriTest {
  private static final String LINK =
      "https://www3.tivo.com/tivo-tco/program/show.do";

  private static Bundle parse(String uri) {
    Bundle bundle = new Bundle();
    ExploreTabs.uriToBundle(Uri.parse(uri), bundle);
    return bundle;
  }

  @Test
  public void readsAllThreeIdsOutOfTheQuery() {
    Bundle bundle = parse(LINK + "?collectionId=tivo:cl.1"
        + "&contentId=tivo:ct.2&offerId=tivo:of.3");
    assertEquals("tivo:cl.1", bundle.getString("collectionId"));
    assertEquals("tivo:ct.2", bundle.getString("contentId"));
    assertEquals("tivo:of.3", bundle.getString("offerId"));
  }

  @Test
  public void anIdThatIsNotThereIsLeftOutRatherThanNulled() {
    // ExploreTabs reads these back with getString() and getRequest() branches
    // on which are non-null, so an absent one has to stay absent.
    Bundle bundle = parse(LINK + "?collectionId=tivo:cl.1");
    assertTrue(bundle.containsKey("collectionId"));
    assertFalse(bundle.containsKey("contentId"));
    assertFalse(bundle.containsKey("offerId"));
    assertNull(bundle.getString("contentId"));
  }

  @Test
  public void aLinkWithNoQueryLeavesTheBundleEmpty() {
    assertTrue(parse(LINK).isEmpty());
    assertTrue(parse(LINK + "?").isEmpty());
  }

  @Test
  public void otherQueryParametersAreIgnored() {
    Bundle bundle = parse(LINK + "?collectionId=tivo:cl.1&utm_source=x"
        + "&recordingId=tivo:rc.9");
    assertEquals(1, bundle.size());
    assertEquals("tivo:cl.1", bundle.getString("collectionId"));
  }

  @Test
  public void anExistingBundleIsAddedToRatherThanReplaced() {
    // The activity hands its own extras in and the link fills the gaps.
    Bundle bundle = new Bundle();
    bundle.putString("contentId", "from-extras");
    bundle.putBoolean("refresh", true);
    ExploreTabs.uriToBundle(Uri.parse(LINK + "?collectionId=tivo:cl.1"),
        bundle);

    assertEquals("tivo:cl.1", bundle.getString("collectionId"));
    assertEquals("from-extras", bundle.getString("contentId"));
    assertTrue(bundle.getBoolean("refresh"));
  }

  @Test
  public void aLinkIdOverridesTheExtraOfTheSameName() {
    Bundle bundle = new Bundle();
    bundle.putString("collectionId", "stale");
    ExploreTabs.uriToBundle(Uri.parse(LINK + "?collectionId=tivo:cl.1"),
        bundle);
    assertEquals("tivo:cl.1", bundle.getString("collectionId"));
  }

  @Test
  public void percentEncodedIdsAreDecoded() {
    Bundle bundle = parse(LINK + "?collectionId=tivo%3Acl.1");
    assertEquals("tivo:cl.1", bundle.getString("collectionId"));
  }

  @Test
  public void anOpaqueUriIsDeclinedRatherThanThrown() {
    // getQueryParameter() throws UnsupportedOperationException on one of
    // these.  The manifest filter only delivers https links, but the activity
    // is exported, so anything on the device can send it whatever it likes.
    assertTrue(parse("tivo:show?collectionId=tivo:cl.1").isEmpty());
    assertTrue(parse("mailto:nobody@example.com").isEmpty());
  }

  @Test
  public void theManifestActuallyRoutesThoseLinksHere() {
    // Parsing is no use if nothing delivers the intent.
    Intent intent = new Intent(Intent.ACTION_VIEW,
        Uri.parse(LINK + "?collectionId=tivo:cl.1"));
    intent.addCategory(Intent.CATEGORY_BROWSABLE);
    PackageManager packages =
        RuntimeEnvironment.getApplication().getPackageManager();
    List<ResolveInfo> handlers = packages.queryIntentActivities(intent, 0);

    assertFalse("no activity claims the show.do link", handlers.isEmpty());
    assertEquals(ExploreTabs.class.getName(),
        handlers.get(0).activityInfo.name);
  }
}
