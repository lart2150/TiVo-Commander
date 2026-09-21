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

import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.util.Rational;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.VideoSize;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player.Listener;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.ui.PlayerView;

import com.arantius.tivocommander.rpc.MindRpc;
import com.arantius.tivocommander.stream.StreamSession;
import com.arantius.tivocommander.stream.TivoHlsDataSource;

/**
 * Plays a recording on this device rather than the television.  The box has
 * very few transcoders and expires none, so the session is handed back
 * whenever this screen stops being visible: teardown onStop, rebuild onStart.
 */
@UnstableApi
public class Player extends BaseActivity {
  public static final String EXTRA_RECORDING_ID = "recordingId";
  public static final String EXTRA_TITLE = "title";

  /** Package-private broadcast: the only way a PiP action can reach back. */
  private static final String ACTION_CONTROL =
      "com.arantius.tivocommander.PIP_CONTROL";
  private static final String EXTRA_CONTROL = "control";
  private static final int CONTROL_PLAY = 1;
  private static final int CONTROL_PAUSE = 2;
  private static final int CONTROL_REWIND = 3;
  private static final int CONTROL_FORWARD = 4;

  @Nullable private ExoPlayer mPlayer;
  @Nullable private StreamSession mSession;
  private PlayerView mPlayerView;
  private TextView mStatus;
  private View mTopBar;
  private String mRecordingId;

  private long mResumePositionMs;

  private Rational mAspectRatio = new Rational(16, 9);

  /** What PictureInPictureParams accepts; anything else throws. */
  private static final float MIN_PIP_RATIO = 1f / 2.39f;
  private static final float MAX_PIP_RATIO = 2.39f;

  /** Registered only while in PiP, which is the only time it can fire. */
  @Nullable private BroadcastReceiver mPipReceiver;

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContent(R.layout.player);

    mPlayerView = findViewById(R.id.player_view);
    mStatus = findViewById(R.id.player_status);
    mTopBar = findViewById(R.id.player_top_bar);

    // An action bar costs enough height on a phone held landscape to pillarbox
    // a 16:9 picture, so this goes edge to edge and the title and Up arrow move
    // into an overlay riding with the transport controls.
    if (getSupportActionBar() != null) {
      getSupportActionBar().hide();
    }
    WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
    findViewById(R.id.player_back).setOnClickListener(new View.OnClickListener() {
      public void onClick(View v) {
        finish();
      }
    });
    mPlayerView.setControllerVisibilityListener(
        new PlayerView.ControllerVisibilityListener() {
          public void onVisibilityChanged(int visibility) {
            final boolean shown = visibility == View.VISIBLE;
            mTopBar.setVisibility(shown ? View.VISIBLE : View.GONE);
            setSystemBarsShown(shown);
          }
        });

    mRecordingId = getIntent().getStringExtra(EXTRA_RECORDING_ID);
    final String title = getIntent().getStringExtra(EXTRA_TITLE);
    if (title != null) {
      ((TextView) findViewById(R.id.player_title)).setText(title);
    }

    if (savedInstanceState != null) {
      mResumePositionMs = savedInstanceState.getLong("positionMs", 0);
    }

    if (mRecordingId == null) {
      Utils.logError("Player started with no recording id");
      finish();
    }
  }

  @Override
  protected void onStart() {
    super.onStart();
    if (mRecordingId == null) {
      return;
    }
    // Every other RPC-backed screen does this; without it a Player restored
    // after process death runs against a null mTivoDevice, which is static.
    if (MindRpc.init(this, null)) {
      return;
    }

    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    setSystemBarsShown(false);
    setStatus(getString(R.string.player_starting));

    // Compared by identity below: onStop/onStart replaces this field rather
    // than nulling it, so a slow answer from a session that has already been
    // closed would otherwise overwrite its successor's state.
    final StreamSession session = new StreamSession(this, MindRpc.mTivoDevice);
    mSession = session;
    session.open(mRecordingId, new StreamSession.Callback() {
      public void onStreamReady(String playlistUrl) {
        if (mSession != session) {
          return;
        }
        startPlayback(playlistUrl);
      }

      public void onStreamFailed(String message, boolean retryable) {
        if (mSession != session) {
          return;
        }
        Utils.logError("Player: " + message);
        setStatus(message);
        Utils.toast(Player.this, message, Toast.LENGTH_LONG);
      }
    });
  }

  @Override
  protected void onStop() {
    super.onStop();
    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    // Closing the PiP window stops the activity without a mode change.
    unregisterPipReceiver();
    releaseEverything();
  }

  /** Entering PiP calls onPause but not onStop, so the session lives on. */
  @Override
  protected void onUserLeaveHint() {
    super.onUserLeaveHint();
    if (mPlayer == null || !mPlayer.isPlaying() || isInPictureInPictureMode()) {
      return;
    }
    try {
      enterPictureInPictureMode(buildPipParams());
    } catch (IllegalStateException e) {
      Utils.log("Player: could not enter PiP -- " + e.getMessage());
    }
  }

  @Override
  public void onPictureInPictureModeChanged(
      boolean isInPictureInPictureMode, Configuration newConfig) {
    super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
    mPlayerView.setUseController(!isInPictureInPictureMode);
    if (isInPictureInPictureMode) {
      mTopBar.setVisibility(View.GONE);
      registerPipReceiver();
    } else {
      unregisterPipReceiver();
    }
  }

  private void registerPipReceiver() {
    if (mPipReceiver != null) {
      return;
    }
    mPipReceiver = new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {
        if (mPlayer == null || !ACTION_CONTROL.equals(intent.getAction())) {
          return;
        }
        final int control = intent.getIntExtra(EXTRA_CONTROL, 0);
        if (control == CONTROL_REWIND) {
          mPlayer.seekBack();
        } else if (control == CONTROL_FORWARD) {
          mPlayer.seekForward();
        } else {
          mPlayer.setPlayWhenReady(control == CONTROL_PLAY);
        }
      }
    };
    ContextCompat.registerReceiver(this, mPipReceiver,
        new IntentFilter(ACTION_CONTROL), ContextCompat.RECEIVER_NOT_EXPORTED);
  }

  private void unregisterPipReceiver() {
    if (mPipReceiver == null) {
      return;
    }
    unregisterReceiver(mPipReceiver);
    mPipReceiver = null;
  }

  /**
   * Skip back, play/pause, skip forward.  The skips use the player's own
   * seekBack/seekForward, so they cannot drift from the full-screen controls.
   * Three actions is only the common limit, so a smaller one keeps play/pause.
   */
  private PictureInPictureParams buildPipParams() {
    final boolean playing = mPlayer != null && mPlayer.getPlayWhenReady();
    final List<RemoteAction> actions = new ArrayList<RemoteAction>();

    if (getMaxNumPictureInPictureActions() >= 3) {
      actions.add(action(CONTROL_REWIND, R.drawable.ic_rewind,
          R.string.player_rewind));
    }
    actions.add(playing
        ? action(CONTROL_PAUSE, R.drawable.ic_pause, R.string.player_pause)
        : action(CONTROL_PLAY, R.drawable.ic_play, R.string.player_play));
    if (getMaxNumPictureInPictureActions() >= 3) {
      actions.add(action(CONTROL_FORWARD, R.drawable.ic_forward,
          R.string.player_forward));
    }

    return new PictureInPictureParams.Builder()
        .setAspectRatio(pipAspectRatio())
        .setActions(actions)
        .build();
  }

  /**
   * setAspectRatio throws outside its accepted range, and updatePipParams runs
   * on every play/pause, so a stream reporting an odd size would crash rather
   * than look wrong.
   */
  private Rational pipAspectRatio() {
    final float ratio =
        (float) mAspectRatio.getNumerator() / mAspectRatio.getDenominator();
    if (ratio < MIN_PIP_RATIO || ratio > MAX_PIP_RATIO) {
      return new Rational(16, 9);
    }
    return mAspectRatio;
  }

  /** The control code doubles as the request code, so extras stay distinct. */
  private RemoteAction action(int control, int iconId, int labelId) {
    final Intent intent = new Intent(ACTION_CONTROL)
        .setPackage(getPackageName())
        .putExtra(EXTRA_CONTROL, control);
    final PendingIntent pending = PendingIntent.getBroadcast(this, control,
        intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    final String label = getString(labelId);
    return new RemoteAction(
        Icon.createWithResource(this, iconId), label, label, pending);
  }

  private void updatePipParams() {
    if (isInPictureInPictureMode()) {
      setPictureInPictureParams(buildPipParams());
    }
  }

  /** Swipe brings the bars back, so hiding them is never a trap. */
  private void setSystemBarsShown(boolean shown) {
    final WindowInsetsControllerCompat controller =
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
    controller.setSystemBarsBehavior(
        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    if (shown) {
      controller.show(WindowInsetsCompat.Type.systemBars());
    } else {
      controller.hide(WindowInsetsCompat.Type.systemBars());
    }
  }

  @Override
  protected void onSaveInstanceState(Bundle outState) {
    super.onSaveInstanceState(outState);
    outState.putLong("positionMs", mResumePositionMs);
  }

  private void startPlayback(String playlistUrl) {
    Utils.log("Player: streaming " + playlistUrl);

    final ExoPlayer player = new ExoPlayer.Builder(this).build();
    final HlsMediaSource source =
        new HlsMediaSource.Factory(new TivoHlsDataSource.Factory())
            .createMediaSource(MediaItem.fromUri(playlistUrl));

    player.addListener(new Listener() {
      @Override
      public void onPlayerError(PlaybackException error) {
        Utils.logError("Player: playback error", error);
        setStatus(getString(R.string.player_error));
      }

      @Override
      public void onRenderedFirstFrame() {
        mStatus.setVisibility(View.GONE);
      }

      @Override
      public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
        if (mSession != null) {
          mSession.setPaused(!playWhenReady);
        }
        // The PiP action shows the opposite of the current state.
        updatePipParams();
      }

      @Override
      public void onVideoSizeChanged(VideoSize videoSize) {
        if (videoSize.width > 0 && videoSize.height > 0) {
          mAspectRatio = new Rational(videoSize.width, videoSize.height);
        }
      }
    });

    player.setMediaSource(source);
    if (mResumePositionMs > 0) {
      player.seekTo(mResumePositionMs);
    }
    player.setPlayWhenReady(true);
    player.prepare();

    mPlayerView.setPlayer(player);
    mPlayer = player;
  }

  private void releaseEverything() {
    if (mPlayer != null) {
      mResumePositionMs = mPlayer.getCurrentPosition();
      mPlayerView.setPlayer(null);
      mPlayer.release();
      mPlayer = null;
    }
    if (mSession != null) {
      mSession.close();
      mSession = null;
    }
    mStatus.setVisibility(View.VISIBLE);
  }

  /**
   * The PlayerView is never hidden: a SurfaceView in a GONE parent gets no
   * surface, so video would decode without ever rendering.
   */
  private void setStatus(String text) {
    mStatus.setText(text);
    mStatus.setVisibility(View.VISIBLE);
  }
}
