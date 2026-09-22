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

import com.arantius.tivocommander.rpc.MindRpc;

/**
 * How far along the service connection is.
 *
 * Despite the name this answers once, with the phase the box is in right now,
 * so following a connection means asking repeatedly.  The reply carries a
 * coarse "phase" and a finer "status"; the phases seen on a real connection
 * are preparing, calling, connecting, downloading, importing, then succeeded.
 */
public class PhoneHomeStatus extends MindRpcRequest {
  public PhoneHomeStatus() {
    super("phoneHomeStatusEventRegister");
    mDataMap.put("bodyId", MindRpc.mTivoDevice.tsn);
  }
}
