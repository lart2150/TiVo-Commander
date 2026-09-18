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
package com.arantius.tivocommander;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Downloads one image (memory cache -> disk cache -> network) and drops it
 * into an ImageView.
 *
 * <p>This used to extend AsyncTask, which is deprecated.  The replacement is
 * deliberately a <em>single</em>-thread executor rather than a pool:
 * AsyncTask.execute() dispatched to AsyncTask.SERIAL_EXECUTOR, so image
 * downloads have always run strictly one at a time and in submission order.
 * This class writes straight into the ImageView it was handed, with no
 * staleness check of its own, so running downloads concurrently would let an
 * out-of-order finish stamp the wrong picture onto a recycled list row.
 * (ArtworkLoader guards its own rows by tagging the view; the callers that go
 * through here directly do not.)  Keeping the queue serial keeps the
 * behaviour the image paths were verified against.
 */
public class DownloadImageTask {
    private static final ExecutorService EXECUTOR =
            Executors.newSingleThreadExecutor();
    private static final Handler MAIN_HANDLER =
            new Handler(Looper.getMainLooper());

    private final Context mContext;
    private final ImageView mImageView;
    private final View mProgressView;

    // TTL settings (7 days)
    private static final long WEEK_MILLIS = 7L * 24L * 60L * 60L * 1000L; // TTL
    private static final boolean DELETE_STALE_FILE = true;


    private static LruCache<String, Bitmap> sMemoryCache;
    private static synchronized LruCache<String, Bitmap> getMemoryCache() {
        if (sMemoryCache == null) {
            final int maxMemoryKb = (int) (Runtime.getRuntime().maxMemory() / 1024);
            final int cacheSizeKb = Math.min(maxMemoryKb / 8, 65536);// 1/8th total ram or 64MB
            sMemoryCache = new LruCache<String, Bitmap>(cacheSizeKb) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount() / 1024;
                }
            };
        }
        return sMemoryCache;
    }

    public DownloadImageTask(Context context, ImageView imageView, View progressView) {
        mContext = context.getApplicationContext();
        mImageView = imageView;
        mProgressView = progressView;
    }

    /** Queues the download.  Safe to call from the main thread. */
    public void execute(final String url) {
        // A bitmap we already hold should not wait behind the queue: the
        // executor is deliberately serial, so scrolling back to a row whose
        // image is in memory would otherwise block on every download queued
        // ahead of it -- up to 35s each against a sleeping TiVo.
        final Bitmap cached = (url == null) ? null : peekMemoryCache(url);
        if (cached != null) {
            onPostExecute(cached);
            return;
        }

        EXECUTOR.execute(new Runnable() {
            public void run() {
                final Bitmap result = doInBackground(url);
                MAIN_HANDLER.post(new Runnable() {
                    public void run() {
                        onPostExecute(result);
                    }
                });
            }
        });
    }

    /** The in-memory bitmap for a url, or null; no I/O, safe on any thread. */
    private static Bitmap peekMemoryCache(String originalUrl) {
        String normalized = normalizeUrl(originalUrl);
        if (normalized == null) {
            return null;
        }
        final String key = sha256(normalized);
        return getMemoryCache().get(key != null ? key : normalized);
    }

    /**
     * The url a bitmap is cached under: the original, upgraded to https unless
     * it points at the TiVo on the local network.  Null if it will not parse.
     */
    private static String normalizeUrl(String originalUrl) {
        if (originalUrl == null) {
            return null;
        }
        try {
            URL url = new URL(originalUrl);
            if (url.getProtocol().equals("http")
                && !url.getHost().matches(
                    "^(10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}"
                    + "|192\\.168\\.\\d{1,3}\\.\\d{1,3}"
                    + "|172\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})$"
                )
            ) {
                url = new URL(originalUrl.replace("http://", "https://"));
            }
            return url.toString();
        } catch (MalformedURLException e) {
            Utils.logError("Parse URL; " + originalUrl, e);
            return null;
        }
    }

    private Bitmap doInBackground(String originalUrl) {
        final String normalized = normalizeUrl(originalUrl);
        if (normalized == null) {
            return null;
        }

        final URL url;
        try {
            url = new URL(normalized);
        } catch (MalformedURLException e) {
            // normalizeUrl() parsed it once already; this cannot happen.
            Utils.logError("Parse URL; " + normalized, e);
            return null;
        }

        final String key = sha256(normalized);
        final String cacheKey = (key != null) ? key : normalized;

        // 1) Memory cache
        Bitmap fromMem = getMemoryCache().get(cacheKey);
        if (fromMem != null) {
            return fromMem;
        }

        // 2) Disk cache (with TTL check)
        Bitmap fromDisk = loadBitmapFromDiskWithTtl(cacheKey, WEEK_MILLIS);
        if (fromDisk != null) {
            getMemoryCache().put(cacheKey, fromDisk);
            return fromDisk;
        }

        // 3) Download, decode, and cache
        byte[] bytes = null;
        try {
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setDoInput(true);
            conn.setUseCaches(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.connect();

            try (InputStream is = new BufferedInputStream(conn.getInputStream());
                 ByteArrayOutputStream bos = new ByteArrayOutputStream(32 * 1024)) {

                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    bos.write(buffer, 0, read);
                }
                bytes = bos.toByteArray();
            }

            if (bytes.length == 0) return null;

            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bitmap == null) return null;

            saveBytesToDisk(cacheKey, bytes);
            touchCacheFile(cacheKey, System.currentTimeMillis());

            getMemoryCache().put(cacheKey, bitmap);

            return bitmap;

        } catch (NullPointerException | IOException e) {
            Utils.logError("Download URL; " + url, e);
            return null;
        }
    }

    /** Runs on the main thread, posted from {@link #execute(String)}. */
    private void onPostExecute(Bitmap result) {
        if (result != null) {
            BitmapDrawable d = new BitmapDrawable(mContext.getResources(), result);
            if (mImageView != null) {
                mImageView.setImageDrawable(d);
            }
        }
        if (mProgressView != null) {
            mProgressView.setVisibility(View.GONE);
        }
    }
    private File getCacheDir() {
        return mContext.getCacheDir();
    }

    private File cacheFileForKey(String key) {
        return new File(getCacheDir(), "img_" + key + ".bin");
    }

    private Bitmap loadBitmapFromDiskWithTtl(String key, long ttlMillis) {
        File f = cacheFileForKey(key);
        if (!f.exists()) return null;

        long now = System.currentTimeMillis();
        long age = now - f.lastModified();
        if (age > ttlMillis) {
            // Expired
            if (DELETE_STALE_FILE) {
                // Best-effort delete to keep cache tidy
                // (Ignore result; if it fails, it will be overwritten later)
                // noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            return null;
        }

        // Optional: "touch" on access to extend freshness window (disabled by default)
        // touchCacheFile(key, now);

        // Decode
        try (FileInputStream fis = new FileInputStream(f);
             BufferedInputStream bis = new BufferedInputStream(fis)) {
            Bitmap bmp = BitmapFactory.decodeStream(bis);
            if (bmp != null) return bmp;
        } catch (IOException ignored) { }

        // Fallback: byte[] path
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] data = new byte[(int) f.length()];
            int read = fis.read(data);
            if (read <= 0) return null;
            return BitmapFactory.decodeByteArray(data, 0, read);
        } catch (IOException e) {
            Utils.logError("Read cache file failed; " + f.getAbsolutePath(), e);
            return null;
        }
    }

    private void saveBytesToDisk(String key, byte[] data) {
        File f = cacheFileForKey(key);
        try (FileOutputStream fos = new FileOutputStream(f);
             BufferedOutputStream bos = new BufferedOutputStream(fos)) {
            bos.write(data);
            bos.flush();
        } catch (IOException e) {
            Utils.logError("Write cache file failed; " + f.getAbsolutePath(), e);
        }
    }

    // TTL: set lastModified on cache file
    private void touchCacheFile(String key, long timestamp) {
        File f = cacheFileForKey(key);
        if (f.exists()) {
            // Best-effort; return value indicates success but we don't need it
            // noinspection ResultOfMethodCallIgnored
            f.setLastModified(timestamp);
        }
    }

    // ---------------------------- Utilities ----------------------------------

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(s.getBytes());
            return toHex(dig);
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16));
            sb.append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }
}