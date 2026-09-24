package com.arantius.tivocommander;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

public class Help extends BaseActivity {
  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContent(R.layout.help);
    setTitle("Help");

    Bundle bundle = getIntent().getExtras();
    if (bundle != null) {
      ((TextView) findViewById(R.id.note)).setText(bundle.getString("note"));
      findViewById(R.id.note).setVisibility(View.VISIBLE);
    }
  }

  @Override
  protected void onPause() {
    super.onPause();
    Utils.log("Activity:Pause:Help");
  }

  @Override
  protected void onResume() {
    super.onResume();
    Utils.log("Activity:Resume:Help");
  }

  public final void sendReport(View v) {

    AlertDialog.Builder builder = new AlertDialog.Builder(this);
    builder.setTitle("Help me help you!");
    builder.setMessage(
        "Sorry you're having trouble.  But I'm just one guy, giving this app "
        + "away for free.  Please help me help you: describe in detail "
        + "exactly what you did, and be prepared to answer my followup "
        + "questions.");

    // TODO: Be DRY vs. the same in customDevice().
    final OnClickListener onClickListener =
        new OnClickListener() {
          public void onClick(DialogInterface dialog, int which) {
            sendReport();
          }
        };
    builder.setPositiveButton("OK", onClickListener);
    builder.setNegativeButton("Cancel", null);

    builder.create().show();
  }

  /**
   * Most of the log the report carries, in characters.  The whole intent has
   * to fit through a binder transaction (about 1MB, at two bytes a char), and
   * one error body or stack trace can make a single line of the buffer huge.
   */
  private static final int MAX_REPORT_LOG = 200000;

  void sendReport() {
    // The log goes in the body rather than as an attachment: a file:// URI
    // handed to another app throws on Android 7+, and the body is what every
    // mail app can take.
    String log = Utils.logBufferAsString();
    if (log.length() > MAX_REPORT_LOG) {
      // The end is what led up to the problem, so that is what is kept.
      log = "(earlier lines cut)\n"
          + log.substring(log.length() - MAX_REPORT_LOG);
    }

    Intent i = new Intent(Intent.ACTION_SEND);
    i.setType("message/rfc822");
    i.putExtra(Intent.EXTRA_EMAIL, new String[] { "play@lart2150.com" });
    i.putExtra(Intent.EXTRA_SUBJECT,
        "Error Log " + Utils.getVersion(this) + " DVR Commander");
    i.putExtra(Intent.EXTRA_TEXT, "Please explain the problem here:\n\n\n\n"
        + "Log data for the developer:\n\n"
        + "Version: " + Utils.getVersion(this) + "\n"
        + "Device: " + Build.MANUFACTURER + " " + Build.MODEL
        + ", Android " + Build.VERSION.RELEASE + "\n\n"
        + "Raw logs:\n" + log);

    try {
      this.startActivity(Intent.createChooser(i, "Send mail..."));
    } catch (android.content.ActivityNotFoundException ex) {
      Utils.toast(this, "There are no email clients installed.",
          Toast.LENGTH_SHORT);
    }

    finish();
  }
}
