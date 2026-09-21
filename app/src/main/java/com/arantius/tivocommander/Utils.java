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

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Calendar;
import java.util.ConcurrentModificationException;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;

import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

public class Utils {
  public static boolean DEBUG_LOG = false;
  public static int LOG_BUFFER_SIZE = 1000;
  private static final String LOG_TAG = "tivo_commander";

  private static final ArrayDeque<String> mLogBuffer = new ArrayDeque<String>(
      LOG_BUFFER_SIZE);
  private static final SimpleDateFormat mLogDateFormat =
      new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
  private static final ObjectMapper mMapper = new ObjectMapper();
  private static final ObjectWriter mMapperPretty = mMapper
      .writerWithDefaultPrettyPrinter();

  /**
   * Turn on the action bar Up arrow.  This used to cast every Activity to
   * AppCompatActivity, which threw on the plain framework Activities the app
   * was built from, so it was emptied out and the Up arrow disappeared
   * everywhere.  The screens are AppCompat activities now; the guard stays so
   * that a screen which is not can never crash here again.
   */
  public final static void activateHomeButton(Activity activity) {
    if (!(activity instanceof AppCompatActivity)) {
      return;
    }
    if (activity instanceof NowShowing) {
      // Now Showing is the app's home; there is nothing above it.
      return;
    }
    ActionBar ab = ((AppCompatActivity) activity).getSupportActionBar();
    if (ab != null) {
      ab.setDisplayHomeAsUpEnabled(true);
      ab.setHomeButtonEnabled(true);
    }
  }

  private final static Class<? extends Activity> activityForMenuId(int menuId) {
    // if/else rather than switch: resource ids are not compile-time constants.
    if (menuId == R.id.menu_item_about) {
      return About.class;
    } else if (menuId == R.id.menu_item_settings) {
      return Settings.class;
    } else if (menuId == R.id.menu_item_devices) {
      return Discover.class;
    } else if (menuId == R.id.menu_item_help) {
      return Help.class;
    } else if (menuId == R.id.menu_item_remote) {
      return Remote.class;
    } else if (menuId == R.id.menu_item_my_shows) {
      return MyShows.class;
    } else if (menuId == R.id.menu_item_search) {
      return Search.class;
    } else if (menuId == R.id.menu_item_season_pass) {
      return SeasonPass.class;
    } else if (menuId == R.id.menu_item_todo) {
      return ToDo.class;
    }
    return null;
  }

  final static void addToMenu(Menu menu, Activity activity, int itemId,
      int iconId, String title, int showAsAction) {
    if (Utils.activityForMenuId(itemId) == activity.getClass()) {
      return;
    }
    MenuItem menuitem = menu.add(Menu.NONE, itemId, Menu.NONE, title);
    menuitem.setIcon(iconId);
    // No SDK_INT guard: setShowAsAction() and the SHOW_AS_ACTION_* constants
    // arrived in API 11 and minSdk is 29, so the old HONEYCOMB check here --
    // and the @SuppressLint annotations that went with it -- were dead.
    menuitem.setShowAsAction(showAsAction);
  }

  public final static void createFullOptionsMenu(Menu menu, Activity activity) {
    addToMenu(menu, activity, R.id.menu_item_remote, R.drawable.icon_remote,
        "Remote", MenuItem.SHOW_AS_ACTION_IF_ROOM);
    addToMenu(menu, activity, R.id.menu_item_my_shows, R.drawable.icon_tv32,
        "My Shows", MenuItem.SHOW_AS_ACTION_IF_ROOM);
    addToMenu(menu, activity, R.id.menu_item_search, R.drawable.icon_search,
        "Search", MenuItem.SHOW_AS_ACTION_IF_ROOM);
    addToMenu(menu, activity, R.id.menu_item_todo, R.drawable.icon_todo,
        "To Do List", MenuItem.SHOW_AS_ACTION_NEVER);
    addToMenu(menu, activity, R.id.menu_item_season_pass,
        R.drawable.icon_seasonpass,
        "Season Pass Manager", MenuItem.SHOW_AS_ACTION_NEVER);
    addToMenu(menu, activity, R.id.menu_item_devices, R.drawable.icon_devices,
        "Devices", MenuItem.SHOW_AS_ACTION_NEVER);
    addToMenu(menu, activity, R.id.menu_item_settings, R.drawable.icon_help,
        "Settings", MenuItem.SHOW_AS_ACTION_NEVER);
    addToMenu(menu, activity, R.id.menu_item_help, R.drawable.icon_help,
        "Help", MenuItem.SHOW_AS_ACTION_NEVER);
    addToMenu(menu, activity, R.id.menu_item_about, R.drawable.icon_info,
        "About", MenuItem.SHOW_AS_ACTION_NEVER);
  }

  public final static void createShortOptionsMenu(Menu menu, Activity activity) {
    addToMenu(menu, activity,
        R.id.menu_item_settings, R.drawable.icon_cog,
        "Settings", MenuItem.SHOW_AS_ACTION_NEVER);
    addToMenu(menu, activity, R.id.menu_item_help, R.drawable.icon_help,
        "Help", MenuItem.SHOW_AS_ACTION_NEVER);
    addToMenu(menu, activity, R.id.menu_item_about, R.drawable.icon_info,
        "About", MenuItem.SHOW_AS_ACTION_NEVER);
  }

  /**
   * The best artwork url on a row, or null when it carries none.
   *
   * TiVo's own host is preferred over anything else on offer.  The box hands
   * out copies on the cable provider's CDN too -- raw IPs on odd ports, or
   * names like atlanticbbpubfevip.pa.vod.atlanticbb.net -- and those are
   * frequently unreachable from the customer's own network, where they do not
   * refuse the connection but swallow it until the timeout expires.  Since the
   * service now reports many images as 1x1, size cannot choose between them
   * either, so the host is the only signal worth acting on.  Largest still
   * wins within each group, and a row offering nothing but provider copies
   * still gets one rather than no picture at all.
   */
  public static final String findImageUrl(JsonNode node) {
    String url = null;
    int biggestSize = 0;
    String otherUrl = null;
    int biggestOtherSize = 0;
    for (JsonNode image : node.path("image")) {
      final String imageUrl = image.path("imageUrl").asText();
      final int size =
          image.path("width").asInt() * image.path("height").asInt();
      if (isTivoHosted(imageUrl)) {
        if (size > biggestSize) {
          biggestSize = size;
          url = imageUrl;
        }
      } else if (size > biggestOtherSize) {
        biggestOtherSize = size;
        otherUrl = imageUrl;
      }
    }
    return url != null ? url : otherUrl;
  }

  /** Whether a url points at TiVo's own image host rather than a reseller's. */
  private static boolean isTivoHosted(String url) {
    try {
      final String host = new URI(url).getHost();
      return host != null
          && (host.equals("tivo.com") || host.endsWith(".tivo.com"));
    } catch (URISyntaxException e) {
      // A url we cannot even parse is not one we can vouch for.
      return false;
    }
  }

  public final static String getVersion(Context context) {
    String version = " v";
    try {
      PackageManager pm = context.getPackageManager();
      version += pm.getPackageInfo(context.getPackageName(), 0).versionName;
    } catch (NameNotFoundException e) {
      version = "";
    }
    return version;
  }

  public static final String join(String glue, List<String> strings) {
    Iterator<String> it = strings.iterator();
    StringBuilder out = new StringBuilder();
    String s;
    while (it.hasNext()) {
      s = it.next();
      if (s == null || s == "") {
        continue;
      }
      out.append(s);
      if (it.hasNext()) {
        out.append(glue);
      }
    }

    return out.toString();
  }

  public static final String join(String glue, String... strings) {
    return join(glue, Arrays.asList(strings));
  }

  /**
   * The intent that plays a recording on this device rather than on the TV.
   *
   * Here rather than in each caller so the extra names stay in one place; the
   * Player is the only thing that reads them.  That also keeps the media3
   * opt-in to a single method: Player is @UnstableApi, so every reference to
   * it from outside needs one, and this is the only such reference.
   */
  @androidx.annotation.OptIn(markerClass =
      androidx.media3.common.util.UnstableApi.class)
  public final static android.content.Intent playHereIntent(
      android.content.Context context, String recordingId, String title) {
    android.content.Intent intent = new android.content.Intent(context, Player.class);
    intent.putExtra(Player.EXTRA_RECORDING_ID, recordingId);
    intent.putExtra(Player.EXTRA_TITLE, title);
    return intent;
  }

  public final static void log(String message) {
    Log.i(LOG_TAG, message);
    logAddToBuffer(message, "I");
  }

  public final static synchronized void logAddToBuffer(
      String message, String level) {
    mLogBuffer.add(
        mLogDateFormat.format(Calendar.getInstance().getTime())
            + " " + level + " " + message);
    while (mLogBuffer.size() >= LOG_BUFFER_SIZE) {
      mLogBuffer.pop();
    }
  }

  public final static synchronized String logBufferAsString() {
    StringBuilder sb = new StringBuilder();
    try {
      for (Iterator<String> itr = mLogBuffer.iterator(); itr.hasNext();) {
        sb.append(itr.next());
        sb.append("\n");
      }
    } catch (ConcurrentModificationException e) {
      sb.append("Concurrent modification!");
    }
    return sb.toString();
  }

  public final static void logDebug(String message) {
    if (DEBUG_LOG) {
      Log.d(LOG_TAG, message);
      logAddToBuffer(message, "D");
    }
  }

  public final static void logError(String message) {
    Log.e(LOG_TAG, message);
    logAddToBuffer(message, "E");
  }

  public final static void logError(String message, Throwable e) {
    Log.e(LOG_TAG, message, e);
    logAddToBuffer(message, "E");
    logAddToBuffer(Log.getStackTraceString(e), "E");
  }

  public final static void logRpc(Object obj) {
    String json = Utils.stringifyToPrettyJson(obj);
    for (String line : json.split(System.getProperty("line.separator"))) {
      Utils.logDebug(line);
    }
  }

  /**
   * Handle one of the common menu items.
   *
   * The Up arrow is not one of them; BaseActivity handles that, the same way
   * on every screen.
   */
  public final static boolean onOptionsItemSelected(MenuItem item,
      Activity srcActivity) {
    Class<? extends Activity> targetActivity =
        Utils.activityForMenuId(item.getItemId());
    if (targetActivity == null) {
      // Not ours: a screen's own item, or the overflow affordance itself.
      return false;
    }
    Intent intent = new Intent(srcActivity, targetActivity);
    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
    srcActivity.startActivity(intent);
    return true;
  }

  public final static Date parseDateStr(String dateStr) {
    SimpleDateFormat dateParser = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    return parseDateTimeStr(dateParser, dateStr);
  }

  private final static Date parseDateTimeStr(SimpleDateFormat dateParser,
      String dateStr) {
    TimeZone tz = TimeZone.getTimeZone("UTC");
    dateParser.setTimeZone(tz);
    ParsePosition pp = new ParsePosition(0);
    return dateParser.parse(dateStr, pp);
  }

  public final static Date parseDateTimeStr(String dateStr) {
    SimpleDateFormat dateParser =
        new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
    return parseDateTimeStr(dateParser, dateStr);
  }

  public final static JsonNode parseJson(String json) {
    try {
      return mMapper.readValue(json, JsonNode.class);
    } catch (JsonMappingException e) {
      Log.e(LOG_TAG, "parseJson failure", e);
    } catch (JsonParseException e) {
      Log.e(LOG_TAG, "parseJson failure", e);
    } catch (IOException e) {
      Log.e(LOG_TAG, "parseJson failure", e);
    }
    logError("When parsing:\n" + json);
    return null;
  }

  public final static void showProgress(Activity activity, boolean show) {
    showProgress(activity, activity, show);
  }

  /**
   * Show or hide the screen's progress bar on behalf of one owner.
   *
   * There is one bar per screen and more than one thing driving it: ExploreTabs
   * alone has three pages loading into the host activity's bar.  So the bar
   * cannot just follow the most recent call -- the first page to finish would
   * hide it while the others were still loading.  Each owner says whether it
   * wants the bar, and the bar shows while any of them still does.
   *
   * The owner set lives on the bar itself, so it is dropped along with the
   * view hierarchy when the screen goes away.
   */
  public final static void showProgress(Activity activity, Object owner,
      boolean show) {
    ProgressBar p = getProgressBar(activity);
    if (p == null) {
      // No window content to draw into; nothing to show progress on.
      return;
    }

    @SuppressWarnings("unchecked")
    Set<Object> owners = (Set<Object>) p.getTag(R.id.progress_owners);
    if (owners == null) {
      owners = new HashSet<Object>();
      p.setTag(R.id.progress_owners, owners);
    }
    if (show) {
      owners.add(owner);
    } else {
      owners.remove(owner);
    }

    log("For activity " + activity.getClass().getName() + ", "
        + owner.getClass().getSimpleName() + " showing progress: " + show
        + "; " + owners.size() + " waiting.");
    p.setVisibility(owners.isEmpty() ? View.GONE : View.VISIBLE);
  }

  /**
   * The screen's progress bar, from BaseActivity's container layout.
   *
   * It is part of that layout rather than something built here and added to
   * the content container: setContent() empties the container on every layout
   * swap, which used to take the bar with it and lose whatever it was showing.
   * A screen that asks for progress before it has any content still gets a bar
   * built by hand, into android.R.id.content, which is all there is at that
   * point.
   */
  private final static ProgressBar getProgressBar(Activity activity) {
    ProgressBar p = activity.findViewById(R.id.global_progress);
    if (p != null) {
      return p;
    }

    ViewGroup vg = activity.findViewById(android.R.id.content);
    if (vg == null) {
      return null;
    }
    log("Creating missing progress bar.");
    p = new ProgressBar(
        activity, null, android.R.attr.progressBarStyleHorizontal);
    p.setId(R.id.global_progress);
    p.setIndeterminate(true);
    p.setLayoutParams(new ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT));
    // Appended, not inserted: the last child draws on top, so the bar stays
    // visible over the screen's own layout.
    vg.addView(p);
    return p;
  }

  public final static String stringifyToJson(Object obj) {
    return stringifyToJson(obj, false);
  }

  private final static String stringifyToJson(Object obj, boolean pretty) {
    try {
      if (pretty) {
        return mMapperPretty.writeValueAsString(obj);
      } else {
        return mMapper.writeValueAsString(obj);
      }
    } catch (JsonGenerationException e) {
      Log.e(LOG_TAG, "stringifyToJson failure", e);
    } catch (JsonMappingException e) {
      Log.e(LOG_TAG, "stringifyToJson failure", e);
    } catch (IOException e) {
      Log.e(LOG_TAG, "stringifyToJson failure", e);
    }
    return null;
  }

  public final static String stringifyToPrettyJson(Object obj) {
    return stringifyToJson(obj, true);
  }

  public final static String stripQuotes(String s) {
    if (s.length() <= 1)
      return s;
    if ('"' == s.charAt(0) && '"' == s.charAt(s.length() - 1)) {
      return s.substring(1, s.length() - 1);
    }
    return s;
  }

  public final static SubscriptionType subscriptionTypeForRecording(
      JsonNode recording) {
    if ("inProgress".equals(recording.path("state").asText())) {
      return SubscriptionType.RECORDING;
    }
    String subType = recording.path("subscriptionIdentifier").path(0)
        .path("subscriptionType").asText();
    if ("seasonPass".equals(subType)) {
      return SubscriptionType.SEASON_PASS;
    } else if ("singleOffer".equals(subType)) {
      return SubscriptionType.SINGLE_OFFER;
    } else if ("wishList".equals(subType)) {
      return SubscriptionType.WISHLIST;
    } else {
      logError("Unsupported subscriptionType string: " + subType);
      return null;
    }
  }

  public final static void toast(Activity activity, int messageId, int length) {
    Context ctx = activity.getBaseContext();
    Toast.makeText(ctx, messageId, length).show();
  }

  public final static void toast(Activity activity, String message, int length) {
    Context ctx = activity.getBaseContext();
    Toast.makeText(ctx, message, length).show();
  }

  public final static String ucFirst(String s) {
    if (s == null || s.isEmpty()) {
      // "" is reachable: JsonNode.asText() answers it for a field the service
      // left out, and a credit with no role would otherwise take
      // substring(0, 1) off an empty string and crash the row.
      return s;
    }
    return s.substring(0, 1).toUpperCase(Locale.US) + s.substring(1);
  }
}
