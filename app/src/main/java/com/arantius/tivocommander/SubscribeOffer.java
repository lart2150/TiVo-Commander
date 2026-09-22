package com.arantius.tivocommander;

import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import com.arantius.tivocommander.rpc.MindRpc;
import com.arantius.tivocommander.rpc.request.Subscribe;
import com.arantius.tivocommander.rpc.response.MindRpcResponse;
import com.arantius.tivocommander.rpc.response.MindRpcResponseListener;

public class SubscribeOffer extends SubscribeBase {
  public void doSubscribe(View v) {
    getValues();

    Subscribe request = new Subscribe();
    Bundle bundle = getIntent().getExtras();
    request
        .setOffer(bundle.getString("offerId"), bundle.getString("contentId"));
    subscribeRequestCommon(request);

    Utils.showProgress(this, true);
    MindRpc.addRequest(request, new MindRpcResponseListener() {
      public void onResponse(MindRpcResponse response) {
        Utils.showProgress(SubscribeOffer.this, false);
        if (Utils.isError(response)) {
          // Stay on the form: finishing reads as "it will record".
          String text = Utils.errorText(response);
          Utils.toast(SubscribeOffer.this,
              "".equals(text) ? getString(R.string.error_change_failed)
                  : "Could not record: " + text,
              Toast.LENGTH_SHORT);
          return;
        }
        finish();
      }
    });
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    MindRpc.init(this, getIntent().getExtras());

    setContent(R.layout.subscribe_offer);
    setTitle("Record Episode");
    setUpSpinner(R.id.until, mUntilLabels);
    setUpSpinner(R.id.start, mStartLabels);
    setUpSpinner(R.id.stop, mStopLabels);
  }

  @Override
  protected void onPause() {
    super.onPause();
    Utils.log("Activity:Pause:SubscribeOffer");
  }

  @Override
  protected void onResume() {
    super.onResume();
    Utils.log("Activity:Resume:SubscribeOffer");
    MindRpc.init(this, getIntent().getExtras());
  }
}
