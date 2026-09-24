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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.arantius.tivocommander.Device;
import com.arantius.tivocommander.Fixtures;
import com.arantius.tivocommander.rpc.request.BodyConfigSearch;
import com.arantius.tivocommander.rpc.request.KeyEventSend;
import com.arantius.tivocommander.rpc.request.MindRpcRequest;
import com.arantius.tivocommander.rpc.request.UnifiedItemSearch;

/** The writer thread: what actually reaches the socket, and in what order. */
public class MindRpcOutputTest {
  private ByteArrayOutputStream mSink;
  private MindRpcOutput mOutput;

  @Before
  public void setUp() {
    Device device = new Device();
    device.tsn = Fixtures.TSN;
    MindRpc.mTivoDevice = device;

    mSink = new ByteArrayOutputStream();
    mOutput = new MindRpcOutput(new DataOutputStream(mSink));
  }

  @After
  public void tearDown() throws Exception {
    mOutput.mStopFlag = true;
    mOutput.join(2000);
  }

  /** Run the writer until it has produced at least this many bytes. */
  private byte[] drain(int expected) throws Exception {
    mOutput.start();
    long deadline = System.currentTimeMillis() + 5000;
    while (mSink.size() < expected && System.currentTimeMillis() < deadline) {
      Thread.sleep(10);
    }
    // A moment more, in case it is writing something it should not be.
    Thread.sleep(100);
    return mSink.toByteArray();
  }

  @Test
  public void writesTheRequestExactlyAsItFramedItself() throws Exception {
    MindRpcRequest request = new BodyConfigSearch();
    byte[] expected = request.getBytes();

    mOutput.addRequest(request);
    assertArrayEquals(expected, drain(expected.length));
  }

  @Test
  public void writesQueuedRequestsInOrder() throws Exception {
    MindRpcRequest first = new KeyEventSend('a');
    MindRpcRequest second = new KeyEventSend('b');
    MindRpcRequest third = new KeyEventSend("select");

    mOutput.addRequest(first);
    mOutput.addRequest(second);
    mOutput.addRequest(third);

    int size = first.getBytes().length + second.getBytes().length
        + third.getBytes().length;
    String written = new String(drain(size), StandardCharsets.UTF_8);

    // Typing into the TiVo's search box only works if the letters arrive in
    // the order they were pressed.
    int a = written.indexOf("RpcId: " + first.getRpcId());
    int b = written.indexOf("RpcId: " + second.getRpcId());
    int c = written.indexOf("RpcId: " + third.getRpcId());
    assertTrue("first request should have been written", a >= 0);
    assertTrue("second should follow the first", b > a);
    assertTrue("third should follow the second", c > b);
  }

  @Test
  public void writesMultiByteBodiesWholeAndUndamaged() throws Exception {
    MindRpcRequest request = new UnifiedItemSearch("café*");
    byte[] expected = request.getBytes();
    byte[] written = drainAfterAdding(request, expected.length);

    assertArrayEquals(expected, written);
    assertEquals("byte count should match the declared length",
        expected.length, written.length);
    assertTrue(new String(written, StandardCharsets.UTF_8).contains("café*"));
  }

  private byte[] drainAfterAdding(MindRpcRequest request, int expected)
      throws Exception {
    mOutput.addRequest(request);
    return drain(expected);
  }

  @Test
  public void writesNothingUntilSomethingIsQueued() throws Exception {
    assertEquals(0, drain(0).length);
  }
}
