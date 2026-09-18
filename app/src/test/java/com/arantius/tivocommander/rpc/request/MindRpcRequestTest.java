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

package com.arantius.tivocommander.rpc.request;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

import org.junit.Before;
import org.junit.Test;

import com.arantius.tivocommander.Device;
import com.arantius.tivocommander.Fixtures;
import com.arantius.tivocommander.Utils;
import com.arantius.tivocommander.rpc.MindRpc;
import com.fasterxml.jackson.databind.JsonNode;

/** The wire format, and the shared parts of every request body. */
public class MindRpcRequestTest {
  private static final String CRLF = "\r\n";

  @Before
  public void setUpDevice() {
    // Every request carrying a bodyId reads it off the connected device.
    Device device = new Device();
    device.tsn = Fixtures.TSN;
    device.mak = Fixtures.MAK;
    device.addr = "0.0.0.0";
    MindRpc.mTivoDevice = device;
  }

  private static String wireOf(MindRpcRequest request)
      throws UnsupportedEncodingException {
    return new String(request.getBytes(), StandardCharsets.UTF_8);
  }

  /** "MRPC/2 (header bytes) (body bytes)", split on spaces. */
  private static String[] requestLineOf(MindRpcRequest request)
      throws UnsupportedEncodingException {
    String whole = wireOf(request);
    return whole.substring(0, whole.indexOf(CRLF)).split(" ");
  }

  /**
   * The header block, sliced the way MindRpcInput slices an incoming one.
   *
   * There is no blank line to look for: the request line says how many bytes
   * of headers and of body follow, and the reader takes exactly that many of
   * each.  Slicing by the declared lengths is what makes these tests prove the
   * lengths are right -- searching for a separator would pass even if they
   * were nonsense.
   */
  private static String headersOf(MindRpcRequest request)
      throws UnsupportedEncodingException {
    byte[] wire = request.getBytes();
    int start = wireOf(request).indexOf(CRLF) + 2;
    int length = Integer.parseInt(requestLineOf(request)[1]);
    return new String(wire, start, length, StandardCharsets.UTF_8);
  }

  private static String bodyOf(MindRpcRequest request)
      throws UnsupportedEncodingException {
    byte[] wire = request.getBytes();
    int start = wireOf(request).indexOf(CRLF) + 2
        + Integer.parseInt(requestLineOf(request)[1]);
    return new String(wire, start, wire.length - start,
        StandardCharsets.UTF_8);
  }

  /** The request body, re-read as JSON. */
  private static JsonNode dataOf(MindRpcRequest request) {
    return Utils.parseJson(Utils.stringifyToJson(request.getDataMap()));
  }

  @Test
  public void rpcIdsAreUniqueAndAscending() {
    // The response listener map is keyed by rpc id, so a repeat would hand
    // one request's answer to another request's listener.
    int first = new WhatsOnSearch().getRpcId();
    int second = new WhatsOnSearch().getRpcId();
    int third = new WhatsOnSearch().getRpcId();
    assertTrue(second > first);
    assertTrue(third > second);
  }

  @Test
  public void declaredLengthsSliceTheMessageExactly() throws Exception {
    // The reader on the other end trusts these two numbers absolutely, so
    // they have to land on the byte the headers end and the body begins.
    BodyConfigSearch request = new BodyConfigSearch();
    assertEquals("MRPC/2", requestLineOf(request)[0]);
    // The declared header length covers the headers plus the line break that
    // closes them -- that is the "+ 2" in getBytes().
    assertTrue("headers should end at a line break",
        headersOf(request).endsWith(CRLF));
    assertFalse("and only one, there is no blank line before the body",
        headersOf(request).endsWith(CRLF + CRLF));
    assertTrue("body should be the whole JSON object",
        bodyOf(request).startsWith("{") && bodyOf(request).endsWith("}"));
    assertEquals("declared body length should be the body's byte count",
        bodyOf(request).getBytes("UTF-8").length,
        Integer.parseInt(requestLineOf(request)[2]));
  }

  @Test
  public void lengthsAreCountedInBytesNotCharacters() throws Exception {
    // Counting characters instead of bytes truncates the body on the wire,
    // and the TiVo then answers nothing at all -- a miserable thing to debug.
    UnifiedItemSearch request = new UnifiedItemSearch("café*");
    String body = bodyOf(request);
    assertTrue("keyword should survive into the body",
        body.contains("café*"));
    assertTrue("this body must be wider in bytes than in characters",
        body.getBytes("UTF-8").length > body.length());
    assertEquals(body.getBytes("UTF-8").length,
        Integer.parseInt(requestLineOf(request)[2]));
  }

  @Test
  public void headersCarryTheBodyIdAndRequestType() throws Exception {
    String headers = headersOf(new BodyConfigSearch());
    assertTrue(headers, headers.contains("Type: request"));
    assertTrue(headers, headers.contains("RequestType: bodyConfigSearch"));
    assertTrue(headers, headers.contains("BodyId: " + Fixtures.TSN));
    assertTrue(headers, headers.contains("SchemaVersion: 7"));
    assertTrue(headers, headers.contains("Content-Type: application/json"));
  }

  @Test
  public void responseCountIsMultipleOnlyForStreamingRequests()
      throws Exception {
    // A registration keeps answering; a search answers once.  Declaring
    // "single" for an event register ends the stream after one event.
    assertTrue(headersOf(new VideoPlaybackInfoEventRegister())
        .contains("ResponseCount: multiple"));
    assertTrue(headersOf(new WhatsOnSearch())
        .contains("ResponseCount: multiple"));
    assertTrue(headersOf(new BodyConfigSearch())
        .contains("ResponseCount: single"));
  }

  @Test
  public void cancelIsSentAsItsOwnKindOfMessage() throws Exception {
    // A cancel names the rpc id it cancels in its header and carries no body;
    // its own rpc id never goes on the wire.
    CancelRpc cancel = new CancelRpc(4242);
    String whole = wireOf(cancel);

    assertTrue(whole, whole.contains("Type: cancel"));
    assertTrue(whole, whole.contains("RpcId: 4242"));
    assertEquals("declared body length should be zero",
        0, Integer.parseInt(requestLineOf(cancel)[2]));
  }

  @Test
  public void keyEventSendDistinguishesLettersFromButtons() {
    // A character goes as its ascii code, a named button as the name.
    JsonNode letter = dataOf(new KeyEventSend('a'));
    assertEquals("ascii", letter.path("event").asText());
    assertEquals(97, letter.path("value").asInt());

    JsonNode button = dataOf(new KeyEventSend("reverse"));
    assertEquals("reverse", button.path("event").asText());
    assertFalse("a named button carries no ascii value", button.has("value"));
  }

  @Test
  public void recordingSearchSwitchesShapeForDeletedShows() {
    JsonNode one = dataOf(new RecordingSearch("tivo:rc.16716469"));
    assertEquals("tivo:rc.16716469", one.path("recordingId").path(0).asText());
    assertFalse(one.has("state"));

    JsonNode deleted = dataOf(new RecordingSearch("deleted"));
    assertEquals("idSequence", deleted.path("format").asText());
    assertEquals("deleted", deleted.path("state").path(0).asText());
    assertFalse("the literal deleted is a mode, not an id",
        deleted.has("recordingId"));
  }

  @Test
  public void baseSearchPicksItsTypeFromWhichIdItWasGiven() {
    JsonNode byCollection = dataOf(new ImageSearch("tivo:cl.123", null));
    assertEquals("collectionSearch", byCollection.path("type").asText());
    assertEquals("tivo:cl.123",
        byCollection.path("collectionId").path(0).asText());

    JsonNode byContent = dataOf(new ImageSearch(null, "tivo:ct.456"));
    assertEquals("contentSearch", byContent.path("type").asText());
    assertEquals("tivo:ct.456", byContent.path("contentId").path(0).asText());
  }

  @Test
  public void noSearchAsksForAnImageRuleset() {
    // An exactMatchDimension ruleset matches nothing on the current service,
    // and the service answers by omitting the image field altogether -- so
    // artwork vanishes app-wide with no error anywhere to find.  If a ruleset
    // ever comes back, fail here rather than in the UI.
    MindRpcRequest[] searches = new MindRpcRequest[] {
        new CollectionSearch("tivo:cl.123"),
        new ContentSearch("tivo:ct.456"),
        new ImageSearch("tivo:cl.123", null),
        new CreditsSearch("tivo:cl.123", null),
        new UnifiedItemSearch("trek*"),
        new SuggestionsSearch("tivo:cl.123"),
        new RecordingSearch("tivo:rc.1"),
        new RecordingFolderItemSearch((String) null, "startTime"),
    };
    for (MindRpcRequest search : searches) {
      String body = Utils.stringifyToJson(search.getDataMap());
      assertFalse(search.getReqType() + " must not send an imageRuleset: "
          + body, body.contains("imageRuleset"));
      assertFalse(search.getReqType() + " must not ask for an exact size: "
          + body, body.contains("exactMatchDimension"));
    }
  }
}
