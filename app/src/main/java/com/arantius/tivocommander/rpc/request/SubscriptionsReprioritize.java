package com.arantius.tivocommander.rpc.request;

import java.util.ArrayList;

import com.arantius.tivocommander.rpc.MindRpc;

/**
 * Put every OnePass in a new priority order, highest first.
 *
 * The list goes in "subscriptionIdV2", not "subscriptionId": that is the
 * field kmttg's reorder sends at schema 17.  Untried from this app: nothing
 * sends this yet -- Season Pass's reorder mode stops short of it.
 */
public class SubscriptionsReprioritize extends MindRpcRequest {

  /** @param subscriptionIds Every OnePass's "tivo:sb." id, in order. */
  public SubscriptionsReprioritize(ArrayList<String> subscriptionIds) {
    super("subscriptionsReprioritize");
    mDataMap.put("bodyId", MindRpc.mTivoDevice.tsn);
    mDataMap.put("subscriptionIdV2", subscriptionIds);
  }
}
