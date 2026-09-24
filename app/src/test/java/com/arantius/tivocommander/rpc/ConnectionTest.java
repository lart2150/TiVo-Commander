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

package com.arantius.tivocommander.rpc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import android.app.Activity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;

import com.arantius.tivocommander.Utils;
import com.arantius.tivocommander.rpc.request.BodyConfigSearch;
import com.arantius.tivocommander.rpc.request.CancelRpc;
import com.arantius.tivocommander.rpc.request.MindRpcRequest;
import com.arantius.tivocommander.rpc.request.RecordingSearch;
import com.arantius.tivocommander.rpc.response.MindRpcResponse;
import com.arantius.tivocommander.rpc.response.MindRpcResponseListener;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * The connection itself: which schema it speaks, how it pages long lists,
 * and how it notices that it has died.
 */
@RunWith(RobolectricTestRunner.class)
public class ConnectionTest {
  /** What a box answers to a schema it does not know, as a Bolt said it. */
  private static final JsonNode UNSUPPORTED = Utils.parseJson(
      "{\"code\": \"routingError\", \"text\": \"Unsupported schema version\","
          + " \"type\": \"error\"}");

  private ActivityController<Activity> mController;
  private FakeTivo mTivo;
  private final List<MindRpcResponse> mHeard = new ArrayList<MindRpcResponse>();
  private final MindRpcResponseListener mListener =
      new MindRpcResponseListener() {
        public void onResponse(MindRpcResponse response) {
          mHeard.add(response);
        }
      };

  @Before
  public void setUp() {
    MindRpc.connectionStarted();
    mTivo = FakeTivo.install();
    mController = Robolectric.buildActivity(Activity.class).setup();
    // Connected (the fake says so), so this only records where answers go.
    MindRpc.init(mController.get(), null);
  }

  @After
  public void tearDown() {
    mController.close();
    FakeTivo.uninstall();
    MindRpc.connectionStarted();
  }

  private static JsonNode ids(boolean isBottom, String... ids) {
    StringBuilder list = new StringBuilder();
    for (String id : ids) {
      list.append(list.length() == 0 ? "" : ",").append('"').append(id)
          .append('"');
    }
    return Utils.parseJson("{\"type\": \"idSequence\", \"isBottom\": "
        + isBottom + ", \"objectIdAndType\": [" + list + "]}");
  }

  private static String headersOf(MindRpcRequest request) throws Exception {
    return new String(request.getBytes(), StandardCharsets.UTF_8);
  }

  // Schema.

  @Test
  public void requestsGoOutAtTheSchemaKmttgSends() throws Exception {
    assertEquals(17, MindRpcRequest.getSchemaVersion());
    assertTrue(headersOf(new BodyConfigSearch())
        .contains("SchemaVersion: 17"));
  }

  @Test
  public void aBoxThatRefusesTheSchemaIsAskedAgainAtTheOldOne()
      throws Exception {
    JsonNode config = Utils.parseJson(
        "{\"type\": \"bodyConfigList\", \"bodyConfig\": []}");
    mTivo.answer("bodyConfigSearch", UNSUPPORTED)
        .answer("bodyConfigSearch", config);

    MindRpc.addRequest(new BodyConfigSearch(), mListener);
    mTivo.deliver();

    assertEquals("asked twice: at 17, then again at 14",
        2, mTivo.sent().size());
    assertEquals("the same request, under the same rpc id",
        mTivo.sent().get(0).getRpcId(), mTivo.sent().get(1).getRpcId());
    assertEquals(14, MindRpcRequest.getSchemaVersion());
    assertTrue(headersOf(mTivo.sent().get(1)).contains("SchemaVersion: 14"));
    assertEquals("the listener hears only the real answer",
        1, mHeard.size());
    assertEquals("bodyConfigList", mHeard.get(0).getRespType());
  }

  @Test
  public void aRefusalAtTheOldSchemaIsPassedOnNotRetriedForever() {
    MindRpcRequest.fallBackToOldSchema();
    mTivo.answer("bodyConfigSearch", UNSUPPORTED);

    MindRpc.addRequest(new BodyConfigSearch(), mListener);
    mTivo.deliver();

    assertEquals(1, mTivo.sent().size());
    assertEquals(1, mHeard.size());
    assertTrue(Utils.isError(mHeard.get(0)));
  }

  @Test
  public void aNewConnectionStartsFromTheNewestSchemaAgain() {
    MindRpcRequest.fallBackToOldSchema();
    MindRpc.connectionStarted();
    assertEquals(17, MindRpcRequest.getSchemaVersion());
  }

  @Test
  public void aCancelCarriesTheSchemaAndIsNotWaitedOn() throws Exception {
    CancelRpc cancel = new CancelRpc(5);
    assertTrue(headersOf(cancel).contains("SchemaVersion: 17"));
    assertFalse(cancel.expectsResponse());
  }

  // Paging.

  @Test
  public void aListLongerThanOnePageIsFetchedWhole() {
    // As a Bolt answered 1017 deleted recordings: 1000, then the rest.
    mTivo.answer("recordingSearch", ids(false, "1", "2"))
        .answer("recordingSearch", ids(true, "3"));

    IdSequencePager.fetchAll(() -> new RecordingSearch("deleted"), mListener);
    mTivo.deliver();

    assertEquals(2, mTivo.sent().size());
    assertFalse("the first page starts at the top",
        mTivo.sent().get(0).getDataMap().containsKey("offset"));
    assertEquals("the next starts after what came back",
        2, mTivo.sent().get(1).getDataMap().get("offset"));
    assertEquals("the listener hears once", 1, mHeard.size());
    JsonNode all = mHeard.get(0).getBody().path("objectIdAndType");
    assertEquals(3, all.size());
    assertEquals("3", all.path(2).asText());
  }

  @Test
  public void aListThatFitsOnOnePageIsHandedOnAsItCame() {
    JsonNode page = ids(true, "1", "2");
    mTivo.answer("recordingSearch", page);

    IdSequencePager.fetchAll(() -> new RecordingSearch("deleted"), mListener);
    mTivo.deliver();

    assertEquals(1, mTivo.sent().size());
    assertEquals(page, mHeard.get(0).getBody());
  }

  @Test
  public void aBoxThatRepeatsItsPageIsNotAskedForever() {
    // recordingFolderItemSearch does exactly this: offset is ignored and
    // isBottom is always false.
    mTivo.answer("recordingSearch", ids(false, "1", "2"));

    IdSequencePager.fetchAll(() -> new RecordingSearch("deleted"), mListener);
    mTivo.deliver();

    assertEquals("one page, one repeat, then stop", 2, mTivo.sent().size());
    assertEquals(2, mHeard.get(0).getBody().path("objectIdAndType").size());
  }

  @Test
  public void aRefusedPageIsPassedOn() {
    JsonNode error = Utils.parseJson(
        "{\"type\": \"error\", \"text\": \"refused for the test\"}");
    mTivo.answer("recordingSearch", ids(false, "1"))
        .answer("recordingSearch", error);

    IdSequencePager.fetchAll(() -> new RecordingSearch("deleted"), mListener);
    mTivo.deliver();

    assertEquals(1, mHeard.size());
    assertTrue(Utils.isError(mHeard.get(0)));
  }

  // Noticing a dead link.

  private static MindRpcRequest overdueRequest() {
    return new MindRpcRequest("bodyConfigSearch") {
      @Override
      public long getResponseTimeoutMs() {
        return -1;
      }
    };
  }

  @Test
  public void anAnswerLongOverdueEndsTheSession() {
    MindRpc.requestSent(overdueRequest());
    MindRpc.checkOverdue();
    assertFalse(MindRpc.getBodyIsAuthed());
  }

  @Test
  public void anAnsweredRequestIsNotWaitedOn() {
    MindRpcRequest request = overdueRequest();
    MindRpc.requestSent(request);
    MindRpc.dispatchResponse(new MindRpcResponse(true, request.getRpcId(),
        Utils.parseJson("{\"type\": \"bodyConfigList\"}")));
    MindRpc.checkOverdue();
    assertTrue(MindRpc.getBodyIsAuthed());
  }

  @Test
  public void aCancelledRequestIsNotWaitedOn() {
    MindRpcRequest request = overdueRequest();
    MindRpc.addRequest(request, mListener);
    MindRpc.cancelRequest(request.getRpcId());
    MindRpc.checkOverdue();
    assertTrue(MindRpc.getBodyIsAuthed());
  }

  @Test
  public void theReaderStopsWhenTheBoxHangsUpMidMessage() throws Exception {
    // Declares 100 body bytes and delivers 5.  The old read loop added read()'s
    // -1 to its count and never got there, spinning forever.
    byte[] wire = ("MRPC/2 10 100\r\nIsFinal: tr" + "{\"typ")
        .getBytes(StandardCharsets.UTF_8);
    MindRpcInput input =
        new MindRpcInput(new DataInputStream(new ByteArrayInputStream(wire)));
    input.start();
    input.join(2000);
    assertFalse("the reader should have given up", input.isAlive());
  }

  // The client certificate.

  @Test
  public void certificateDaysCountDownAndGoNegativeOnceExpired() {
    long day = TimeUnit.DAYS.toMillis(1);
    long now = 1_000_000_000_000L;
    assertEquals(90, MindRpc.certDaysLeft(now + 90 * day + 5, now));
    assertEquals(0, MindRpc.certDaysLeft(now + day - 1, now));
    assertEquals(-1, MindRpc.certDaysLeft(now - 1, now));
  }

  /**
   * A tripwire, not a unit test: this starts failing 90 days before the
   * certificate built into the app expires (May 2029 for the current one),
   * which is when users start seeing the warning -- and once it expires no
   * TiVo will accept a connection from the app at all.
   */
  @Test
  public void theBundledCertificateHasTimeLeft() throws Exception {
    File raw = new File("src/main/res/raw");
    String password = new String(
        Files.readAllBytes(new File(raw, "cdata_pass").toPath()),
        StandardCharsets.UTF_8).split("\\R")[0];
    KeyStore store = KeyStore.getInstance("PKCS12");
    try (InputStream in = new FileInputStream(new File(raw, "cdata"))) {
      store.load(in, password.toCharArray());
    }
    long soonest = Long.MAX_VALUE;
    for (Enumeration<String> e = store.aliases(); e.hasMoreElements();) {
      X509Certificate cert = (X509Certificate) store.getCertificate(e.nextElement());
      soonest = Math.min(soonest, cert.getNotAfter().getTime());
    }
    long days = MindRpc.certDaysLeft(soonest, System.currentTimeMillis());
    assertTrue("res/raw/cdata expires in " + days + " days; replace it",
        days >= 90);
  }
}
