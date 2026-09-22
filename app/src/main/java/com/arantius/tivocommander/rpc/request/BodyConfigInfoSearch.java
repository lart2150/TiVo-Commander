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
 * The same bodyConfigSearch as {@link BodyConfigSearch}, asked at a newer
 * schema so the answer includes the network interfaces.
 *
 * At schema 7 -- what the rest of this app speaks -- the reply carries the
 * disk counters and little else: no networkInterface, no timeZoneName.  Those
 * arrive from schema 14, which is what kmttg has always asked this same box
 * for, so the box is known to answer it.
 *
 * Deliberately a separate request rather than a change to BodyConfigSearch.
 * The schema also picks the shape of every *other* response, and My Shows and
 * Now Showing parse the disk meter out of the schema 7 reply; only this screen
 * wants the wider one, so only this screen asks for it.
 */
public class BodyConfigInfoSearch extends MindRpcRequest {
  /** The schema that answers with networkInterface; see the class comment. */
  private static final int SCHEMA_WITH_NETWORK = 14;

  public BodyConfigInfoSearch() {
    super("bodyConfigSearch");
    mDataMap.put("bodyId", MindRpc.mTivoDevice.tsn);
    mSchemaVersion = SCHEMA_WITH_NETWORK;
  }
}
