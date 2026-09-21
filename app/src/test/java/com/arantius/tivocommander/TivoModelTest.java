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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The model table, pinned against TiVo's published service number table
 * (support article 000001490, read 2026-09-21).
 *
 * The transcoder cases are the ones worth being careful about: they decide
 * whether in-app playback is offered at all, and the line does not fall where
 * the model names suggest.
 */
public class TivoModelTest {
  @Test
  public void boltAndEdgeAlwaysTranscode() {
    // Every box in either line shares one prefix, across 2, 4 and 6 tuners.
    assertTrue(TivoModel.hasTranscoder("849000123456789"));  // Bolt
    assertTrue(TivoModel.hasTranscoder("D6E000123456789"));  // Edge for Cable
    assertTrue(TivoModel.hasTranscoder("D6F000123456789"));  // Edge for Antenna
  }

  @Test
  public void onlySixTunerRoamiosTranscode() {
    assertTrue(TivoModel.hasTranscoder("840300123456789"));   // Pro, 6 tuners
    assertTrue(TivoModel.hasTranscoder("848000123456789"));   // Plus, 6 tuners
    // 846 is every four-tuner Roamio there is -- the 500GB and all the OTAs --
    // and none of them has a transcoder.  This is the case a "Roamio streams"
    // assumption gets wrong.
    assertFalse(TivoModel.hasTranscoder("846500123456789"));
    assertEquals("Roamio", TivoModel.forTsn("846500123456789").name);
  }

  @Test
  public void nothingBeforeTheRoamioTranscodes() {
    for (String tsn : new String[] {
        "746500123456789", "748000123456789",
        "750500123456789", "758250123456789" }) {
      assertFalse(tsn, TivoModel.hasTranscoder(tsn));
      assertEquals("Premiere", TivoModel.forTsn(tsn).name);
    }
  }

  @Test
  public void tunerlessCompanionsAreSupportedButDoNotTranscode() {
    // A Mini speaks RPC but owns no recordings to serve.
    for (String tsn : new String[] {
        "A92000123456789", "A93000123456789", "A95000123456789" }) {
      assertTrue(tsn, TivoModel.forTsn(tsn).supported);
      assertFalse(tsn, TivoModel.hasTranscoder(tsn));
    }
  }

  @Test
  public void theStreamIsNotSomethingToConnectTo() {
    // A94 is the transcoder-only accessory: no tuner, no recordings, no UI.
    // It must not read as "has a transcoder", which would be true of the
    // hardware and useless to this app.
    TivoModel stream = TivoModel.forTsn("A94000123456789");
    assertFalse(stream.supported);
    assertFalse(stream.hasTranscoder);
  }

  @Test
  public void seriesThreeAndEarlierAreKnownAndUnsupported() {
    // Known-and-rejected has to stay distinguishable from unrecognised: the
    // two show the user different warnings in Discover.
    for (String tsn : new String[] {
        "658000123456789", "652000123456789", "648000123456789",
        "649000123456789", "540000123456789", "110000123456789" }) {
      TivoModel model = TivoModel.forTsn(tsn);
      assertNotNull(tsn, model);
      assertFalse(tsn, model.supported);
    }
  }

  @Test
  public void virginMediaIsKnownAndUnsupported() {
    for (String tsn : new String[] { "B42", "C00", "C8A", "CF0", "E80" }) {
      assertFalse(tsn, TivoModel.forTsn(tsn + "000123456789").supported);
    }
  }

  @Test
  public void unknownPrefixHasNoOpinion() {
    // Not "unsupported" -- most likely a box newer than this table, which
    // Discover must keep offering Try Anyway for.
    assertNull(TivoModel.forTsn("ZZZ000123456789"));
    assertFalse(TivoModel.hasTranscoder("ZZZ000123456789"));
  }

  @Test
  public void handlesTheFormsATsnActuallyArrivesIn() {
    // A body id carries the prefix, a bare TSN does not, and neither is
    // reliably upper case.
    assertEquals("Bolt", TivoModel.forTsn("tsn:849000123456789").name);
    assertEquals("Bolt", TivoModel.forTsn("TSN:849000123456789").name);
    assertEquals("Edge", TivoModel.forTsn("d6e000123456789").name);
    assertEquals("Edge", TivoModel.forTsn("  D6F000123456789  ").name);
  }

  @Test
  public void copesWithTheUnsetTsn() {
    // Device.tsn is "-" until MindRpc.saveBodyId() has seen a bodyConfig, and
    // a custom device is stored that way, so this is the common case at first
    // run -- not an edge case.
    assertNull(TivoModel.forTsn("-"));
    assertNull(TivoModel.forTsn(""));
    assertNull(TivoModel.forTsn(null));
    assertFalse(TivoModel.hasTranscoder("-"));
  }

  @Test
  public void realBoltTsnFromTheTestBox() {
    // As reported by /sysinfo/json/svcinfo "sg" on the box this was built
    // against.  Shorter than the padded forms above, which is the point.
    assertEquals("Bolt", TivoModel.forTsn("84900019045ED87").name);
    assertTrue(TivoModel.hasTranscoder("84900019045ED87"));
  }
}
