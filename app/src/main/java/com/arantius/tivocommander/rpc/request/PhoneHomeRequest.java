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

package com.arantius.tivocommander.rpc.request;

import com.arantius.tivocommander.rpc.MindRpc;

/**
 * Tell the box to connect to the TiVo service now.
 *
 * The same thing as "Connect to the TiVo service now" in the box's own menus:
 * it refreshes guide data and any pending service messages.  It answers
 * immediately, having only started the connection -- watching it through is
 * {@link PhoneHomeStatus}.
 */
public class PhoneHomeRequest extends MindRpcRequest {
  public PhoneHomeRequest() {
    super("phoneHomeRequest");
    mDataMap.put("bodyId", MindRpc.mTivoDevice.tsn);
  }
}
