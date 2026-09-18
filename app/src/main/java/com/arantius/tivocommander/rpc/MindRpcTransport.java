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

package com.arantius.tivocommander.rpc;

import com.arantius.tivocommander.rpc.request.MindRpcRequest;

/**
 * Where a request goes once MindRpc has accepted it.
 *
 * There is no implementation of this in the app: with none installed MindRpc
 * writes to the socket exactly as it always has.  It exists so that a test can
 * stand in for the TiVo and answer from recorded responses, which is the only
 * way to drive a screen without a box on the network -- every screen asks
 * MindRpc for its data in onCreate and gives up if there is no connection.
 */
public interface MindRpcTransport {
  /** Take one request.  Answer it by calling MindRpc.dispatchResponse(). */
  void send(MindRpcRequest request);
}
