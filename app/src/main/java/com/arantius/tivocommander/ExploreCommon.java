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

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.arantius.tivocommander.rpc.MindRpc;
import com.arantius.tivocommander.rpc.request.CollectionSearch;
import com.arantius.tivocommander.rpc.request.ContentSearch;
import com.arantius.tivocommander.rpc.request.MindRpcRequest;
import com.arantius.tivocommander.rpc.request.RecordingSearch;
import com.arantius.tivocommander.rpc.response.MindRpcResponse;
import com.arantius.tivocommander.rpc.response.MindRpcResponseListener;
import com.fasterxml.jackson.databind.JsonNode;

abstract public class ExploreCommon extends ExploreTabFragment {
  private final MindRpcResponseListener mListener =
      new MindRpcResponseListener() {
        public void onResponse(MindRpcResponse response) {
          if (!isUsable()) {
            return;
          }

          if (Utils.isError(response)) {
            // Every one of these bodies lacks the content this screen is
            // built from.  (The staleData case used to be singled out, but
            // compared a JsonNode to a String, so it never matched and fell
            // through to "Response missing content" like everything else.)
            Utils.log("ExploreCommon: content failed: "
                + response.getBody().path("code").asText() + " "
                + Utils.errorText(response));
            Utils.toast(requireActivity(), R.string.error_load_failed,
                Toast.LENGTH_SHORT);
            requireActivity().finish();
            return;
          }

          JsonNode body = response.getBody();
          if (body.has("collection")) {
            mContent = response.getBody().path("collection").path(0);
          } else if (body.has("recording")) {
            mContent = response.getBody().path("recording").path(0);
          } else if (body.has("content")) {
            mContent = response.getBody().path("content").path(0);
          } else {
            Utils.toast(requireActivity(), "Response missing content",
                Toast.LENGTH_SHORT);
            requireActivity().finish();
            return;
          }
          onContent();
        }
      };

  protected String mCollectionId = null;
  protected JsonNode mContent = null;
  protected String mContentId = null;
  protected String mOfferId = null;
  protected String mRecordingId = null;

  protected MindRpcRequest getRequest() {
    if (mRecordingId != null) {
      return new RecordingSearch(mRecordingId);
    } else if (mContentId != null) {
      return new ContentSearch(mContentId);
    } else if (mCollectionId != null) {
      return new CollectionSearch(mCollectionId);
    } else {
      final String message = "Content: Bad input!";
      Utils.toast(requireActivity(), message, Toast.LENGTH_SHORT);
      Utils.logError(message);
      requireActivity().finish();
      return null;
    }
  }

  abstract protected void onContent();

  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    Bundle args = getArguments();
    if (args != null) {
      mCollectionId = args.getString("collectionId");
      mContentId = args.getString("contentId");
      mOfferId = args.getString("offerId");
      mRecordingId = args.getString("recordingId");
    }
  }

  @Override
  public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);
    showProgress(true);
    MindRpcRequest req = getRequest();
    if (req == null) {
      // getRequest() took the "Bad input!" path: it has already toasted and
      // finished the activity.  MindRpc.addRequest() would dereference the
      // null for its rpc id, so there is nothing left to do here.
      return;
    }
    MindRpc.addRequest(req, mListener);
    startExtraRequests();
  }

  /** Hook for subclasses that need their own requests alongside the common
   * one; called once the view exists. */
  protected void startExtraRequests() {
  }

  protected void setRefreshResult() {
    Intent resultIntent = new Intent();
    resultIntent.putExtra("refresh", true);
    requireActivity().setResult(Activity.RESULT_OK, resultIntent);
  }
}
