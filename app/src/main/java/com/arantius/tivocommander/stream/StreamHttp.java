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

package com.arantius.tivocommander.stream;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import com.arantius.tivocommander.Utils;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Plain HTTP against the TiVo's streaming service on port 49152, separate from
 * MindRpc's authenticated TLS socket on 1413.  Never call from the main thread.
 */
final class StreamHttp {
  private StreamHttp() {
  }

  static final int PORT = 49152;

  private static final int CONNECT_TIMEOUT_MS = 8000;
  private static final int READ_TIMEOUT_MS = 20000;

  static String baseUrl(String addr) {
    return "http://" + addr + ":" + PORT;
  }

  static final class Response {
    final int code;
    final byte[] body;

    Response(int code, byte[] body) {
      this.code = code;
      this.body = body;
    }

    String text() {
      return body == null ? "" : new String(body, StandardCharsets.UTF_8);
    }

    JsonNode json() {
      return Utils.parseJson(text());
    }

    boolean ok() {
      return code == HttpURLConnection.HTTP_OK;
    }

    /** The eTranscoderError this response carries, or 0 for none. */
    int transcoderError() {
      JsonNode json = json();
      if (json == null) {
        return 0;
      }
      String code = json.path("eTranscoderError").asText("");
      if (!code.startsWith("0x")) {
        return 0;
      }
      try {
        return Integer.parseInt(code.substring(2), 16);
      } catch (NumberFormatException e) {
        return 0;
      }
    }

    String describe() {
      int reason = transcoderError();
      return "HTTP " + code
          + (reason == 0 ? "" : " " + TranscoderStatus.errorName(reason));
    }
  }

  /** Null means "could not reach the box"; a non-OK Response means it spoke. */
  static Response get(String url) {
    HttpURLConnection conn = null;
    try {
      conn = (HttpURLConnection) new URL(url).openConnection();
      conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
      conn.setReadTimeout(READ_TIMEOUT_MS);
      int code = conn.getResponseCode();
      InputStream in =
          code < HttpURLConnection.HTTP_BAD_REQUEST
              ? conn.getInputStream() : conn.getErrorStream();
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      if (in != null) {
        try {
          byte[] buf = new byte[8192];
          int n;
          while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
          }
        } finally {
          in.close();
        }
      }
      return new Response(code, out.toByteArray());
    } catch (Exception e) {
      Utils.log("StreamHttp GET failed: " + url + " -- " + e.getMessage());
      return null;
    } finally {
      if (conn != null) {
        conn.disconnect();
      }
    }
  }
}
