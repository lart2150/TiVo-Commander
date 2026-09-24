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

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Real RPC traffic, recorded off a live TiVo and kept under
 * src/test/resources/rpc/.
 *
 * To refresh or extend it: turn on Settings -&gt; debug log, drive the screen,
 * and pull the JSON blocks out of <code>adb logcat -s tivo_commander</code>
 * (Utils.logRpc writes every request and response body there; reassemble per
 * logging thread, they interleave).  On the way in the MAK and TSN are zeroed,
 * object ids are canonicalised to <code>tivo:cl.X</code> so one capture covers
 * any show, and long arrays are cut to three entries.
 *
 * Both requests and responses are stored as numbered variants per RPC type,
 * because the type does not identify the shape: ImageSearch, CollectionSearch
 * and CreditsSearch all go out as a collectionSearch and all come back as a
 * collectionList, asking for and receiving quite different fields.  Variant 1
 * is the largest.
 */
public final class Fixtures {
  public static final String TSN = "tsn:0000000000000000";
  public static final String MAK = "0000000000";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private Fixtures() {
  }

  /** Every distinct captured request body of one RPC type. */
  public static List<JsonNode> requests(String type) {
    return variants("/rpc/request/" + type);
  }

  /** Every distinct captured response body of one RPC type. */
  public static List<JsonNode> responses(String type) {
    return variants("/rpc/response/" + type);
  }

  /** The largest captured response of a type. */
  public static JsonNode response(String type) {
    return responses(type).get(0);
  }

  /** The largest captured response, as the bytes the socket would carry. */
  public static byte[] responseBytes(String type) {
    return response(type).toString().getBytes(StandardCharsets.UTF_8);
  }

  /** Replace object ids with the placeholder the fixtures were stored with. */
  public static String canonicalizeIds(String json) {
    return json.replaceAll("tivo:([a-z]{2})\\.\\d+", "tivo:$1.X");
  }

  private static List<JsonNode> variants(String dir) {
    List<Path> files = new ArrayList<Path>();
    Path path = resourceDir(dir);
    try (DirectoryStream<Path> found = Files.newDirectoryStream(path,
        "*.json")) {
      for (Path file : found) {
        files.add(file);
      }
    } catch (IOException e) {
      throw new AssertionError("Could not list fixtures in " + path, e);
    }
    if (files.isEmpty()) {
      throw new AssertionError("No fixtures in " + path);
    }
    Collections.sort(files);

    List<JsonNode> out = new ArrayList<JsonNode>();
    for (Path file : files) {
      try {
        out.add(parse(new String(Files.readAllBytes(file),
            StandardCharsets.UTF_8), file.toString()));
      } catch (IOException e) {
        throw new AssertionError("Could not read fixture: " + file, e);
      }
    }
    return out;
  }

  private static JsonNode parse(String json, String what) {
    try {
      return MAPPER.readTree(json);
    } catch (IOException e) {
      throw new AssertionError("Fixture is not valid JSON: " + what, e);
    }
  }

  private static Path resourceDir(String path) {
    URL url = Fixtures.class.getResource(path);
    if (url == null) {
      throw new AssertionError("Missing fixture directory: " + path);
    }
    try {
      return Paths.get(url.toURI());
    } catch (URISyntaxException e) {
      throw new AssertionError("Bad fixture path: " + path, e);
    }
  }

  /** The names of every RPC type captured under one of the two directories. */
  public static List<String> capturedTypes(String kind) {
    List<String> out = new ArrayList<String>();
    try (DirectoryStream<Path> found =
        Files.newDirectoryStream(resourceDir("/rpc/" + kind))) {
      for (Path dir : found) {
        out.add(dir.getFileName().toString());
      }
    } catch (IOException e) {
      throw new AssertionError("Could not list /rpc/" + kind, e);
    }
    Collections.sort(out);
    return out;
  }
}
