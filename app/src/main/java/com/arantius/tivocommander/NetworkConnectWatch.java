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

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The rules for following a service connection through to its end.
 *
 * Kept apart from the screen because all of it is judgement about timing, and
 * none of it needs a view: how often to ask, when an answer is really about
 * the previous connection, and when there is nothing left to wait for.
 */
public final class NetworkConnectWatch {
  /**
   * The phases a connection passes through, sampled off a real one.
   *
   * Anything outside this set has stopped making progress.  No failure has
   * been observed, so a phase that is not here is reported as it arrived
   * rather than guessed at.
   */
  private static final List<String> WORKING = Arrays.asList(
      "preparing", "calling", "connecting", "downloading", "importing");

  public static final String SUCCEEDED = "succeeded";

  /** Compiled once: {@link #humanize} is called for every row of a list. */
  private static final Pattern CAMEL_CASE_BOUNDARY =
      Pattern.compile("([a-z])([A-Z])");

  /**
   * How long to keep watching before giving up.
   *
   * A connection that has not run in a while genuinely takes this long
   * sometimes, and giving up is only about this screen -- the box carries on
   * either way.
   */
  public static final long LIMIT_MS = 20 * 60 * 1000L;

  /**
   * The box reports the *previous* connection's outcome until this one has
   * started, so an answer this early that looks finished has not started yet
   * rather than ended.
   */
  public static final long SETTLE_MS = 15 * 1000L;

  private NetworkConnectWatch() {
  }

  /** Is the box still working on it? */
  public static boolean isWorking(String phase) {
    return WORKING.contains(phase);
  }

  /**
   * Should this answer be treated as the end of the connection?
   *
   * Finished-looking answers inside the settle window are the previous run's
   * and mean nothing yet.
   */
  public static boolean isFinished(String phase, long elapsedMs) {
    return !isWorking(phase) && elapsedMs > SETTLE_MS;
  }

  /** Should a finished-looking answer be ignored as the previous run's? */
  public static boolean isStale(String phase, long elapsedMs) {
    return !isWorking(phase) && elapsedMs <= SETTLE_MS;
  }

  public static boolean isExpired(long elapsedMs) {
    return elapsedMs > LIMIT_MS;
  }

  /**
   * How long to wait before asking again.
   *
   * Every poll is a whole request to the box, so this starts responsive while
   * the early phases change quickly and eases off for the long tail.
   */
  public static long pollDelayMs(long elapsedMs) {
    if (elapsedMs < 60 * 1000L) {
      return 2 * 1000L;
    }
    if (elapsedMs < 5 * 60 * 1000L) {
      return 5 * 1000L;
    }
    return 10 * 1000L;
  }

  /** Elapsed time as m:ss, for the running log. */
  public static String clock(long elapsedMs) {
    long seconds = elapsedMs / 1000L;
    return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60);
  }

  /**
   * Turn a status like "preparingToCallOverNetwork" into words.
   *
   * These are not a closed set either, so this splits on camel case rather
   * than mapping a fixed list.
   */
  public static String humanize(String status) {
    if (status == null || "".equals(status)) {
      return "";
    }
    String spaced =
        CAMEL_CASE_BOUNDARY.matcher(status).replaceAll("$1 $2");
    return Utils.ucFirst(spaced.toLowerCase(Locale.US));
  }
}
