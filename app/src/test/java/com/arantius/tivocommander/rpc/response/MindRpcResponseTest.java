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

package com.arantius.tivocommander.rpc.response;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

import com.arantius.tivocommander.Fixtures;

/** Turning the bytes off the socket back into a response object. */
public class MindRpcResponseTest {
  private static byte[] utf8(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  private static MindRpcResponse parse(String headers, String body) {
    return new MindRpcResponseFactory().create(utf8(headers), utf8(body));
  }

  private static final String BODY = "{\"type\": \"whatsOnList\"}";

  @Test
  public void readsRpcIdAndIsFinalFromTheHeaders() {
    MindRpcResponse response = parse(
        "Type: response\r\nRpcId: 42\r\nIsFinal: true\r\n", BODY);
    assertNotNull(response);
    assertEquals(42, response.getRpcId());
    assertTrue(response.isFinal());
  }

  @Test
  public void anEventStreamIsNotFinal() {
    MindRpcResponse response = parse(
        "Type: response\r\nRpcId: 7\r\nIsFinal: false\r\n", BODY);
    assertFalse(response.isFinal());
    assertEquals(7, response.getRpcId());
  }

  @Test
  public void defaultsWhenTheHeadersSayNothing() {
    MindRpcResponse response = parse("Type: response\r\n", BODY);
    assertEquals(0, response.getRpcId());
    assertTrue("a response with no IsFinal is treated as the last one",
        response.isFinal());
  }

  @Test
  public void headerOrderDoesNotMatter() {
    MindRpcResponse response = parse(
        "IsFinal: false\r\nContent-Type: application/json\r\nRpcId: 9\r\n",
        BODY);
    assertEquals(9, response.getRpcId());
    assertFalse(response.isFinal());
  }

  @Test
  public void typeComesFromTheBody() {
    MindRpcResponse response = parse("RpcId: 1\r\n",
        "{\"type\": \"recordingList\"}");
    assertEquals("recordingList", response.getRespType());
  }

  @Test
  public void missingTypeReadsAsEmptyRatherThanNull() {
    MindRpcResponse response = parse("RpcId: 1\r\n", "{}");
    assertEquals("", response.getRespType());
  }

  @Test
  public void anErrorResponseStillParses() {
    MindRpcResponse response = parse("RpcId: 3\r\n",
        "{\"type\": \"error\", \"text\": \"no such thing\"}");
    assertNotNull(response);
    assertEquals("error", response.getRespType());
    assertEquals("no such thing", response.getBody().path("text").asText());
  }

  @Test
  public void unparseableBodyGivesNullRatherThanThrowing() {
    // Runs on the socket reader thread: an exception there takes the whole
    // connection down instead of dropping one bad message.
    assertNull(parse("RpcId: 1\r\n", "{ this is not json"));
  }

  @Test
  public void parsesACapturedAuthenticationResponse() {
    MindRpcResponse response = new MindRpcResponseFactory().create(
        utf8("Type: response\r\nRpcId: 1\r\nIsFinal: true\r\n"),
        Fixtures.responseBytes("bodyAuthenticateResponse"));
    assertNotNull(response);
    assertEquals("bodyAuthenticateResponse", response.getRespType());
    assertEquals("success", response.getBody().path("status").asText());
    assertFalse("failure".equals(response.getBody().path("status").asText()));
  }

  @Test
  public void parsesACapturedListResponse() {
    MindRpcResponse response = new MindRpcResponseFactory().create(
        utf8("RpcId: 12\r\nIsFinal: true\r\n"),
        Fixtures.responseBytes("recordingFolderItemList"));
    assertNotNull(response);
    assertEquals("recordingFolderItemList", response.getRespType());
    assertTrue("captured list should hold rows",
        response.getBody().path("recordingFolderItem").size() > 0);
  }
}
