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

import android.app.Activity;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import androidx.preference.PreferenceManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.SparseArray;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.WindowManager;
import android.widget.EditText;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.arantius.tivocommander.rpc.MindRpc;
import com.arantius.tivocommander.rpc.request.KeyEventSend;

public class Remote extends BaseActivity implements OnClickListener {
  private EditText mEditText;
  private String mLastString = null;
  private Vibrator mVibrator;

  private final TextWatcher mTextWatcher = new TextWatcher() {
    public void afterTextChanged(Editable s) {
    }

    public void beforeTextChanged(CharSequence s, int start, int count,
        int after) {
    }

    public void onTextChanged(CharSequence s, int start, int before, int count) {
      // In case of screen rotation, Android restores the contents of the
      // (hidden) EditText. Pull it out for no-change-change detection.
      if (mLastString == null) {
        mLastString = mEditText.getText().toString();
      }

      String newString = s.toString();
      String oldString = new String(mLastString);
      mLastString = newString;

      if (newString.equals(oldString)) {
        // No actual change; e.g. screen rotation fired a fake one.
        return;
      }

      if (before > count) {
        while (before > count) {
          MindRpc.addRequest(viewIdToEvent(R.id.remote_reverse), null);
          count++;
        }
      } else {
        for (int i = start + before; i < start + count; i++) {
          // TODO: Only send legal characters.
          MindRpc.addRequest(new KeyEventSend(s.charAt(i)), null);
        }
      }
    }
  };

  public void onClick(View v) {
    if (v.getId() == R.id.remote_clear) {
      mLastString = "";
      mEditText.setText("");
    }

    boolean doVibrate =
        PreferenceManager.getDefaultSharedPreferences(this.getBaseContext())
            .getBoolean("remote_vibrate", true);

    if (mVibrator != null && doVibrate) {
      mVibrator.vibrate(
          VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE));
    }

    MindRpc.addRequest(viewIdToEvent(v.getId()), null);
  }

  /**
   * Remote button view id -> the event it sends.
   *
   * Lookup tables rather than switch statements: resource ids are not
   * compile-time constants, so they cannot be case labels.
   */
  private static final SparseArray<String> EVENT_STR_BY_VIEW_ID =
      new SparseArray<String>();
  private static final SparseArray<Character> EVENT_CHAR_BY_VIEW_ID =
      new SparseArray<Character>();

  // @formatter:off
  static {
    EVENT_STR_BY_VIEW_ID.put(R.id.remote_tivo,        "tivo");
    EVENT_STR_BY_VIEW_ID.put(R.id.remote_liveTv,      "liveTv");
    EVENT_STR_BY_VIEW_ID.put(R.id.remote_info,        "info");
    EVENT_STR_BY_VIEW_ID.put(R.id.remote_zoom,        "zoom");
    EVENT_STR_BY_VIEW_ID.put(R.id.remote_back,        "back");
    EVENT_STR_BY_VIEW_ID.put(R.id.remote_guide,       "guide");
    EVENT_STR_BY_VIEW_ID.put(R.id.remote_up,          "up");
    EVENT_STR_BY_VIEW_ID.put(R.id.remote_down,        "down");
    EVENT_STR_BY_VIEW_ID.put(R.id.remote_left,        "left");
    EVENT_STR_BY_VIEW_ID.put(R.id.remote_right,       "right");
    EVENT_STR_BY_VIEW_ID.put(R.id.remote_select,      "select");
    EVENT_STR_BY_VIEW_ID.put(R.id.remote_channelUp,   "channelUp");
    EVENT_STR_BY_VIEW_ID.put(R.id.remote_channelDown, "channelDown");
    EVENT_STR_BY_VIEW_ID.put(R.id.remote_thumbsDown,  "thumbsDown");
    EVENT_STR_BY_VIEW_ID.put(R.id.remote_thumbsUp,    "thumbsUp");
    EVENT_STR_BY_VIEW_ID.put(R.id.remote_record,      "record");
    EVENT_STR_BY_VIEW_ID.put(R.id.remote_play,        "play");
    EVENT_STR_BY_VIEW_ID.put(R.id.remote_pause,       "pause");
    EVENT_STR_BY_VIEW_ID.put(R.id.remote_reverse,     "reverse");
    EVENT_STR_BY_VIEW_ID.put(R.id.remote_forward,     "forward");
    EVENT_STR_BY_VIEW_ID.put(R.id.remote_slow,        "slow");
    EVENT_STR_BY_VIEW_ID.put(R.id.remote_replay,      "replay");
    EVENT_STR_BY_VIEW_ID.put(R.id.remote_advance,     "advance");
    EVENT_STR_BY_VIEW_ID.put(R.id.remote_actionA,     "actionA");
    EVENT_STR_BY_VIEW_ID.put(R.id.remote_actionB,     "actionB");
    EVENT_STR_BY_VIEW_ID.put(R.id.remote_actionC,     "actionC");
    EVENT_STR_BY_VIEW_ID.put(R.id.remote_actionD,     "actionD");
    EVENT_STR_BY_VIEW_ID.put(R.id.remote_clear,       "clear");
    EVENT_STR_BY_VIEW_ID.put(R.id.remote_enter,       "enter");

    EVENT_CHAR_BY_VIEW_ID.put(R.id.remote_num1, '1');
    EVENT_CHAR_BY_VIEW_ID.put(R.id.remote_num2, '2');
    EVENT_CHAR_BY_VIEW_ID.put(R.id.remote_num3, '3');
    EVENT_CHAR_BY_VIEW_ID.put(R.id.remote_num4, '4');
    EVENT_CHAR_BY_VIEW_ID.put(R.id.remote_num5, '5');
    EVENT_CHAR_BY_VIEW_ID.put(R.id.remote_num6, '6');
    EVENT_CHAR_BY_VIEW_ID.put(R.id.remote_num7, '7');
    EVENT_CHAR_BY_VIEW_ID.put(R.id.remote_num8, '8');
    EVENT_CHAR_BY_VIEW_ID.put(R.id.remote_num9, '9');
    EVENT_CHAR_BY_VIEW_ID.put(R.id.remote_num0, '0');
  }
  // @formatter:on

  public static KeyEventSend viewIdToEvent(int id) {
    String eventStr = EVENT_STR_BY_VIEW_ID.get(id);
    if (eventStr != null) {
      return new KeyEventSend(eventStr);
    }

    Character eventChar = EVENT_CHAR_BY_VIEW_ID.get(id);
    if (eventChar != null) {
      return new KeyEventSend(eventChar.charValue());
    }

    return null;
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    MindRpc.init(this, null);

    setContent(R.layout.remote);
    setTitle("Remote");

    // It says always, but it only suppresses the open-on-launch.
    getWindow().setSoftInputMode(
        WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);

    mEditText = (EditText) findViewById(R.id.keyboard_activator);
    mEditText.addTextChangedListener(mTextWatcher);
    // getSystemService(Class) rather than the Context.VIBRATOR_SERVICE string,
    // which was deprecated in API 31 in favour of VibratorManager.  The class
    // lookup keeps working on every level we support and still hands back the
    // default vibrator on API 31+.
    mVibrator = getSystemService(Vibrator.class);
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    Utils.createFullOptionsMenu(menu, this);
    return true;
  }

  @Override
  public boolean onKeyUp(int keyCode, KeyEvent event) {
    char sendChar = '\0';
    if (KeyEvent.KEYCODE_0 <= keyCode && keyCode <= KeyEvent.KEYCODE_9) {
      sendChar = (char) ((int) '0' + keyCode - KeyEvent.KEYCODE_0);
    } else if (KeyEvent.KEYCODE_A <= keyCode && keyCode <= KeyEvent.KEYCODE_Z) {
      sendChar = (char) ((int) 'a' + keyCode - KeyEvent.KEYCODE_A);
    } else if (KeyEvent.KEYCODE_SPACE == keyCode) {
      sendChar = ' ';
    } else if (KeyEvent.KEYCODE_DEL == keyCode) {
      MindRpc.addRequest(viewIdToEvent(R.id.remote_reverse), null);
      return true;
    }

    if (sendChar != '\0') {
      MindRpc.addRequest(new KeyEventSend(sendChar), null);
      return true;
    } else {
      return super.onKeyUp(keyCode, event);
    }
  }

  @Override
  protected void onPause() {
    super.onPause();
    Utils.log("Activity:Pause:Remote");
  }

  @Override
  protected void onResume() {
    super.onResume();
    Utils.log("Activity:Resume:Remote");
    MindRpc.init(this, null);
  }

  public void toggleKeyboard(View v) {
    // Keep mLastString in step with the text we are about to set, or the
    // watcher reads the clear as a deletion and sends one "reverse" per
    // character that was in the box.
    mLastString = "";
    mEditText.setText("");
    mEditText.requestFocus();

    // InputMethodManager.toggleSoftInputFromWindow() is deprecated; the
    // supported way to drive the IME is the window insets controller, which
    // has no toggle, so read the current visibility first.  WindowInsetsCompat
    // derives IME visibility from the bottom inset below API 30 and asks the
    // platform above it, so this is accurate all the way down to minSdk.
    final int ime = WindowInsetsCompat.Type.ime();
    final WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(mEditText);
    final WindowInsetsControllerCompat controller =
        WindowCompat.getInsetsController(getWindow(), mEditText);
    if (insets != null && insets.isVisible(ime)) {
      controller.hide(ime);
    } else {
      controller.show(ime);
    }
  }
}
