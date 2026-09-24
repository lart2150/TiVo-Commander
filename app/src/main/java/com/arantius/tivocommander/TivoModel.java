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

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * What a TiVo is, from the first three of its service number (TiVo support
 * articles 000001490, read 2026-09-21, and 000001411).  A hint, not the
 * authority: Device.tsn is "-" until saveBodyId() sees a bodyConfig.
 */
public final class TivoModel {
  public final String name;
  public final boolean supported;
  /**
   * Has a built-in transcoder.  Every Bolt and Edge does; only the Roamio line
   * splits, where six-tuner Pro (840) and Plus (848) do and every four-tuner
   * Roamio (OTA included) is an 846 and does not.
   */
  public final boolean hasTranscoder;

  private TivoModel(String name, boolean supported, boolean hasTranscoder) {
    this.name = name;
    this.supported = supported;
    this.hasTranscoder = hasTranscoder;
  }

  private static final Map<String, TivoModel> BY_PREFIX = buildTable();

  private static Map<String, TivoModel> buildTable() {
    Map<String, TivoModel> m = new HashMap<String, TivoModel>();

    put(m, new TivoModel("Edge", true, true), "D6E", "D6F");
    put(m, new TivoModel("Bolt", true, true), "849");

    put(m, new TivoModel("Roamio Pro", true, true), "840");
    put(m, new TivoModel("Roamio Plus", true, true), "848");
    put(m, new TivoModel("Roamio", true, false), "846");

    put(m, new TivoModel("Premiere", true, false), "746", "748", "750", "758");

    put(m, new TivoModel("Mini", true, false), "A92", "A93", "A95");

    // A transcoder with no tuner, recordings or UI: nothing to connect to.
    put(m, new TivoModel("Stream", false, false), "A94");

    put(m, new TivoModel("TiVo HD XL", false, false), "658");
    put(m, new TivoModel("TiVo HD", false, false), "652", "663");
    put(m, new TivoModel("Series3 HD", false, false), "648");

    put(m, new TivoModel("Series2 DT", false, false), "649");
    put(m, new TivoModel("Humax DVD Writer", false, false), "595");
    put(m, new TivoModel("Humax Series2", false, false), "590");
    put(m, new TivoModel("Toshiba DVD Writer", false, false), "565");
    put(m, new TivoModel("Samsung DirecTV", false, false), "382");
    put(m, new TivoModel("Hughes HD DTV", false, false), "357");
    put(m, new TivoModel("Hughes DirecTV", false, false), "351");
    put(m, new TivoModel("RCA DirecTV", false, false), "321");
    put(m, new TivoModel("Philips DirecTV", false, false), "301");
    put(m, new TivoModel("Pioneer DVD", false, false), "275");
    put(m, new TivoModel("Toshiba DVD Player", false, false), "264");
    put(m, new TivoModel("Hughes Satellite", false, false), "151");
    put(m, new TivoModel("RCA DTV", false, false), "121");
    for (String p : new String[] {
        "542", "540", "240", "230", "140", "130", "110" }) {
      put(m, new TivoModel("Series2 " + p, false, false), p);
    }

    put(m, new TivoModel("Virgin Media", false, false),
        "B42", "C00", "C8A", "CF0", "E80");

    put(m, new TivoModel("TiVo (A90)", true, false), "A90");
    put(m, new TivoModel("TiVo (D18)", true, false), "D18");

    return Collections.unmodifiableMap(m);
  }

  private static void put(
      Map<String, TivoModel> m, TivoModel model, String... prefixes) {
    for (String prefix : prefixes) {
      m.put(prefix, model);
    }
  }

  /** Null means "no opinion" (likely a newer box), not "incompatible". */
  public static TivoModel forTsn(String tsn) {
    if (tsn == null) {
      return null;
    }
    String bare = tsn.trim();
    if (bare.regionMatches(true, 0, "tsn:", 0, 4)) {
      bare = bare.substring(4);
    }
    if (bare.length() < 3) {
      return null;
    }
    return BY_PREFIX.get(bare.substring(0, 3).toUpperCase(Locale.US));
  }

  public static boolean hasTranscoder(String tsn) {
    TivoModel model = forTsn(tsn);
    return model != null && model.hasTranscoder;
  }

  @Override
  public String toString() {
    return name;
  }
}
