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
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The timing rules for following a service connection. */
public class NetworkConnectWatchTest {
  private static final long SECOND = 1000L;
  private static final long MINUTE = 60 * SECOND;

  @Test
  public void theWorkingPhasesAreTheOnesSeenOnARealConnection() {
    for (String phase : new String[] { "preparing", "calling", "connecting",
        "downloading", "importing" }) {
      assertTrue(phase + " is progress", NetworkConnectWatch.isWorking(phase));
    }
    assertFalse(NetworkConnectWatch.isWorking("succeeded"));
    assertFalse(NetworkConnectWatch.isWorking("unknown"));
  }

  @Test
  public void aFinishedAnswerTooEarlyIsThePreviousConnectionsAndIsIgnored() {
    // The box reports the last connection's outcome until this one gets
    // going, so "succeeded" two seconds in is not this run finishing.
    assertTrue(NetworkConnectWatch.isStale("succeeded", 2 * SECOND));
    assertFalse(NetworkConnectWatch.isFinished("succeeded", 2 * SECOND));

    // Past the settle window the same answer does mean this run.
    assertFalse(NetworkConnectWatch.isStale("succeeded", 30 * SECOND));
    assertTrue(NetworkConnectWatch.isFinished("succeeded", 30 * SECOND));
  }

  @Test
  public void workInProgressIsNeverStaleOrFinished() {
    assertFalse(NetworkConnectWatch.isStale("calling", 2 * SECOND));
    assertFalse(NetworkConnectWatch.isFinished("calling", 2 * SECOND));
    assertFalse(NetworkConnectWatch.isFinished("calling", 10 * MINUTE));
  }

  @Test
  public void aPhaseThatIsNotProgressEndsTheWatchWhateverItIsCalled() {
    // No failure phase has ever been seen, so anything unrecognised has to
    // end the watch rather than leave it polling for twenty minutes.
    assertTrue(NetworkConnectWatch.isFinished("somethingElse", MINUTE));
  }

  @Test
  public void pollingStartsQuickAndEasesOff() {
    assertEquals(2 * SECOND, NetworkConnectWatch.pollDelayMs(0));
    assertEquals(2 * SECOND, NetworkConnectWatch.pollDelayMs(59 * SECOND));
    assertEquals(5 * SECOND, NetworkConnectWatch.pollDelayMs(2 * MINUTE));
    assertEquals(10 * SECOND, NetworkConnectWatch.pollDelayMs(10 * MINUTE));
  }

  @Test
  public void theWatchGivesUpEventually() {
    assertFalse(NetworkConnectWatch.isExpired(19 * MINUTE));
    assertTrue(NetworkConnectWatch.isExpired(21 * MINUTE));
  }

  @Test
  public void elapsedTimeReadsAsAClock() {
    assertEquals("0:00", NetworkConnectWatch.clock(0));
    assertEquals("0:07", NetworkConnectWatch.clock(7 * SECOND));
    assertEquals("1:05", NetworkConnectWatch.clock(65 * SECOND));
    assertEquals("12:30", NetworkConnectWatch.clock(750 * SECOND));
  }

  @Test
  public void statusesReadAsWordsRatherThanCamelCase() {
    assertEquals("Preparing to call over network",
        NetworkConnectWatch.humanize("preparingToCallOverNetwork"));
    assertEquals("Dialing over network",
        NetworkConnectWatch.humanize("dialingOverNetwork"));
    assertEquals("Succeeded", NetworkConnectWatch.humanize("succeeded"));
    assertEquals("", NetworkConnectWatch.humanize(""));
    assertEquals("", NetworkConnectWatch.humanize(null));
  }
}
