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

package com.arantius.tivocommander.stream;

import java.util.Locale;

/** Transcribed from the JavaScript a Bolt serves at :49152/sysinfo. */
public final class TranscoderStatus {
  private TranscoderStatus() {
  }

  public static final int STATE_READY = 6;
  public static final int STATE_DISABLED = 4;

  /**
   * A segment not transcoded yet.  The box's table calls 0x0202
   * BADLY_FORMED_REQUEST; this is its empirical meaning, since asking past the
   * transcoder answers this rather than a 404.
   */
  public static final int ERR_NOT_TRANSCODED_YET = 0x0202;

  public static final int ERR_MAX_SESSIONS_EXCEEDED = 0x0206;

  /**
   * The client list is full -- a different limit from the one above: every
   * client that ever registered holds a slot (12 on a Bolt), forever.
   */
  public static final int ERR_CLIENT_LIMIT_EXCEEDED = 0x0303;

  public static final int ERR_SESSION_NOT_FOUND = 0x0501;
  public static final int ERR_RECORDING_NOT_FOUND = 0x0502;
  public static final int ERR_RECORDING_TOO_LONG = 0x0503;
  public static final int ERR_CONTENT_PROTECTED = 0x0504;
  public static final int ERR_CONTENT_INVALID_FORMAT = 0x0505;

  public static String stateName(int state) {
    switch (state) {
      case 0: return "Initializing";
      case 1: return "InGuidedSetup";
      case 2: return "RebootRequired";
      case 3: return "SoftwareUpdateRequired";
      case 4: return "Disabled";
      case 5: return "PreconditionFailed";
      case 6: return "Ready";
      case 7: return "ThermalShutdown";
      default: return "Unknown " + state;
    }
  }

  public static String errorName(int code) {
    switch (code) {
      case 0x0100: return "INITIALISING";
      case 0x0101: return "IN_GUIDED_SETUP";
      case 0x0102: return "REBOOT_REQUIRED";
      case 0x0103: return "SOFTWARE_UPDATE_REQUIRED";
      case 0x0104: return "DISABLED";
      case 0x0105: return "PRECONDITION_FAILED";
      case 0x0107: return "THERMAL_SHUTDOWN";
      case 0x0201: return "INTERNAL_ERROR";
      case 0x0202: return "BADLY_FORMED_REQUEST";
      case 0x0203: return "PASS_KEY_FAILED";
      case 0x0204: return "URL_TOKEN_NOT_FOUND";
      case 0x0205: return "URL_TOKEN_FAILED";
      case 0x0206: return "MAX_SESSIONS_EXCEEDED";
      case 0x0207: return "HOST_NOT_FOUND";
      case 0x0208: return "SERVICE_NOT_AVAILABLE";
      case 0x0209: return "UNKNOWN_EXTENSION";
      case 0x020A: return "REMOTE_DVR_NOT_SUPPORTED";
      case 0x0301: return "CLIENT_LIST_RESET_FAILED";
      case 0x0302: return "CLIENT_LIST_RESET_TOO_SOON";
      case 0x0303: return "CLIENT_LIMIT_EXCEEDED";
      case 0x0304: return "CLIENT_LIST_UUID_MISSING";
      case 0x0305: return "CLIENT_LIST_BAD_REQUEST";
      case 0x0401: return "DAK_NOT_FOUND";
      case 0x0402: return "DAK_VERSION_MISSING";
      case 0x0403: return "DAK_INTERNAL_ERROR";
      case 0x0501: return "SESSION_NOT_FOUND";
      case 0x0502: return "SESSION_RECORDING_NOT_FOUND";
      case 0x0503: return "SESSION_RECORDING_TOO_LONG";
      case 0x0504: return "SESSION_CONTENT_PROTECTED";
      case 0x0505: return "SESSION_CONTENT_INVALID_FORMAT";
      case 0x0506: return "SESSION_RTT_FAILED";
      default: return String.format(Locale.US, "0x%04X", code);
    }
  }

  /** About this recording, not this moment.  Everything else is transient. */
  public static boolean isPermanent(int code) {
    return code == ERR_RECORDING_NOT_FOUND
        || code == ERR_RECORDING_TOO_LONG
        || code == ERR_CONTENT_PROTECTED
        || code == ERR_CONTENT_INVALID_FORMAT;
  }

  public static boolean isBusy(int code) {
    return code == ERR_MAX_SESSIONS_EXCEEDED
        || code == ERR_CLIENT_LIMIT_EXCEEDED;
  }
}
