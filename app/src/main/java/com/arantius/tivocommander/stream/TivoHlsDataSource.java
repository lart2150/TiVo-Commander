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

package com.arantius.tivocommander.stream;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSourceUtil;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.TransferListener;

/**
 * The player's HTTP source, with TiVo playlists corrected on the way through.
 * Only .m3u8 responses are buffered and rewritten.  Nothing here decrypts: the
 * box leaves the AES-128 IV implicit, which RFC 8216 defines as the media
 * sequence number and media3 already handles.
 */
@UnstableApi
public final class TivoHlsDataSource implements DataSource {
  public static final class Factory implements DataSource.Factory {
    private final DataSource.Factory mUpstream;

    public Factory() {
      this(new DefaultHttpDataSource.Factory()
          .setConnectTimeoutMs(8000)
          .setReadTimeoutMs(20000)
          .setAllowCrossProtocolRedirects(false));
    }

    Factory(DataSource.Factory upstream) {
      mUpstream = upstream;
    }

    @Override
    public DataSource createDataSource() {
      return new TivoHlsDataSource(mUpstream.createDataSource());
    }
  }

  private final DataSource mDelegate;

  @Nullable private byte[] mPlaylist;
  private int mPlaylistOffset;

  /**
   * Remembered because the delegate forgets: a playlist is read whole and
   * closed, and a closed DefaultHttpDataSource returns null from getUri() --
   * which ParsingLoadable asserts on, failing every playlist load.
   */
  @Nullable private Uri mUri;

  private TivoHlsDataSource(DataSource delegate) {
    mDelegate = delegate;
  }

  @Override
  public void addTransferListener(TransferListener transferListener) {
    mDelegate.addTransferListener(transferListener);
  }

  @Override
  public long open(DataSpec dataSpec) throws IOException {
    mPlaylist = null;
    mPlaylistOffset = 0;
    mUri = dataSpec.uri;

    if (!isPlaylist(dataSpec.uri)) {
      final long length = mDelegate.open(dataSpec);
      final Uri resolved = mDelegate.getUri();
      if (resolved != null) {
        mUri = resolved;
      }
      return length;
    }

    mDelegate.open(dataSpec);
    final Uri resolved = mDelegate.getUri();
    if (resolved != null) {
      mUri = resolved;
    }
    final byte[] raw;
    try {
      raw = DataSourceUtil.readToEnd(mDelegate);
    } finally {
      DataSourceUtil.closeQuietly(mDelegate);
    }

    final String rewritten = HlsPlaylistRewriter.rewrite(
        new String(raw, StandardCharsets.UTF_8));
    mPlaylist = rewritten.getBytes(StandardCharsets.UTF_8);
    return mPlaylist.length;
  }

  @Override
  public int read(byte[] buffer, int offset, int length) throws IOException {
    if (mPlaylist == null) {
      return mDelegate.read(buffer, offset, length);
    }
    if (length == 0) {
      return 0;
    }
    final int remaining = mPlaylist.length - mPlaylistOffset;
    if (remaining == 0) {
      return C_RESULT_END_OF_INPUT;
    }
    final int count = Math.min(length, remaining);
    System.arraycopy(mPlaylist, mPlaylistOffset, buffer, offset, count);
    mPlaylistOffset += count;
    return count;
  }

  private static final int C_RESULT_END_OF_INPUT = -1;

  @Override
  @Nullable
  public Uri getUri() {
    return mUri;
  }

  @Override
  public Map<String, List<String>> getResponseHeaders() {
    return mDelegate.getResponseHeaders();
  }

  @Override
  public void close() throws IOException {
    mPlaylist = null;
    mPlaylistOffset = 0;
    mUri = null;
    mDelegate.close();
  }

  private static boolean isPlaylist(@Nullable Uri uri) {
    if (uri == null || uri.getPath() == null) {
      return false;
    }
    return uri.getPath().endsWith(".m3u8");
  }
}
