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

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.arantius.tivocommander.rpc.MindRpc;
import com.arantius.tivocommander.rpc.request.SubscriptionSearch;
import com.arantius.tivocommander.rpc.response.MindRpcResponse;
import com.arantius.tivocommander.rpc.response.MindRpcResponseListener;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;

// TODO: This copies a lot from ShowList; be DRY?
// SeasonPass.java (reorder-related parts consolidated)
// NOTE: Keep your existing imports and RPC code; this focuses on list + reorder.

public class SeasonPass extends AppCompatActivity {

    protected enum SubscriptionStatus { LOADED, LOADING, MISSING; }
    protected static final int MAX_REQUEST_BATCH = 5;

    protected boolean mInReorderMode = false;
    protected int mLongClickPosition;

    // Backing data
    protected final ArrayList<JsonNode> mSubscriptionData = new ArrayList<>();
    protected final ArrayList<String>   mSubscriptionIds  = new ArrayList<>();
    protected final ArrayList<String>   mSubscriptionIdsBeforeReorder = new ArrayList<>();
    protected final ArrayList<SubscriptionStatus> mSubscriptionStatus = new ArrayList<>();
    protected final android.util.SparseArray<ArrayList<Integer>> mRequestSlotMap = new android.util.SparseArray<>();

    // UI
    private RecyclerView mRecyclerView;
    private SubscriptionAdapter mListAdapter;
    private ItemTouchHelper mItemTouchHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (MindRpc.init(this, null)) return;

        Utils.activateHomeButton(this);
        setTitle("Season Pass Manager");
        setContentView(R.layout.list_season_pass);

        // RecyclerView
        mRecyclerView = findViewById(R.id.season_pass_list);
        mRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        mRecyclerView.setHasFixedSize(false);

        // Adapter
        mListAdapter = new SubscriptionAdapter();
        mRecyclerView.setAdapter(mListAdapter);

        // Drag to reorder
        ItemTouchHelper.SimpleCallback dragCallback =
                new ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
                    @Override public boolean isLongPressDragEnabled() {
                        // We start drag only via the handle
                        return false;
                    }
                    @Override public boolean onMove(@NonNull RecyclerView rv,
                                                    @NonNull RecyclerView.ViewHolder from,
                                                    @NonNull RecyclerView.ViewHolder to) {
                        int fromPos = from.getBindingAdapterPosition();
                        int toPos   = to.getBindingAdapterPosition();

                        // Swap in all parallel structures
                        Collections.swap(mSubscriptionData,   fromPos, toPos);
                        Collections.swap(mSubscriptionStatus, fromPos, toPos);
                        Collections.swap(mSubscriptionIds,    fromPos, toPos);

                        mListAdapter.notifyItemMoved(fromPos, toPos);
                        return true;
                    }
                    @Override public void onSwiped(@NonNull RecyclerView.ViewHolder v, int dir) { /* no-op */ }
                };
        mItemTouchHelper = new ItemTouchHelper(dragCallback);
        mItemTouchHelper.attachToRecyclerView(mRecyclerView);

        // Initial load
        startRequest();
    }

    // === Load IDs then item details, same as your existing RPC flow ===
    protected void startRequest() {
        mSubscriptionData.clear();
        mSubscriptionIds.clear();
        mSubscriptionStatus.clear();

        MindRpcResponseListener idSequenceCallback = response -> {
            JsonNode body = response.getBody();
            for (JsonNode node : body.path("objectIdAndType")) {
                mSubscriptionIds.add(node.asText());
            }
            for (int i = 0; i < mSubscriptionIds.size(); i++) {
                mSubscriptionData.add(null);
                mSubscriptionStatus.add(SubscriptionStatus.MISSING);
            }
            mListAdapter.notifyDataSetChanged();
            queueDetailFor();
        };
        MindRpc.addRequest(new SubscriptionSearch(), idSequenceCallback);
    }

    // === Buttons ===
    public void reorderEnable(View v) {
        // Snapshot original order in case of cancel or error
        mSubscriptionIdsBeforeReorder.clear();
        mSubscriptionIdsBeforeReorder.addAll(mSubscriptionIds);

        mInReorderMode = true;
        mListAdapter.notifyDataSetChanged();
        findViewById(R.id.reorder_enable).setVisibility(View.GONE);
        findViewById(R.id.reorder_apply).setVisibility(View.VISIBLE);
    }

    public void reorderApply(View v) {
        // TODO: invoke your existing "apply order to backend" call here,
        // sending mSubscriptionIds in their new order. On success:
        mInReorderMode = false;
        mListAdapter.notifyDataSetChanged();
        findViewById(R.id.reorder_enable).setVisibility(View.VISIBLE);
        findViewById(R.id.reorder_apply).setVisibility(View.GONE);

        // If server rejects, you can revert:
        // restoreOrderFromSnapshot();
    }

    private void restoreOrderFromSnapshot() {
        if (mSubscriptionIdsBeforeReorder.isEmpty()) return;
        // Reorder current arrays to match the saved snapshot order
        ArrayList<JsonNode> newData = new ArrayList<>(mSubscriptionData.size());
        ArrayList<SubscriptionStatus> newStatus = new ArrayList<>(mSubscriptionStatus.size());
        for (String id : mSubscriptionIdsBeforeReorder) {
            int idx = mSubscriptionIds.indexOf(id);
            if (idx >= 0) {
                newData.add(mSubscriptionData.get(idx));
                newStatus.add(mSubscriptionStatus.get(idx));
            }
        }
        mSubscriptionIds.clear();
        mSubscriptionIds.addAll(mSubscriptionIdsBeforeReorder);
        mSubscriptionData.clear();
        mSubscriptionData.addAll(newData);
        mSubscriptionStatus.clear();
        mSubscriptionStatus.addAll(newStatus);
        mListAdapter.notifyDataSetChanged();
    }

    // ===== Adapter =====
    private final class SubscriptionAdapter extends RecyclerView.Adapter<SubscriptionAdapter.VH> {

        SubscriptionAdapter() {
            setHasStableIds(true); // enables better move animations
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_season_pass, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            TextView title   = h.itemView.findViewById(R.id.show_title);
            TextView channel = h.itemView.findViewById(R.id.show_channel);
            ImageView drag   = h.itemView.findViewById(R.id.drag_handle);

            // Show the index
            ((TextView) h.itemView.findViewById(R.id.index)).setText(String.valueOf(position + 1));

            // ====== LAZY LOAD DETAIL (this is what your old getView() did) ======
            SubscriptionStatus status = mSubscriptionStatus.get(position);
            if (status == SubscriptionStatus.MISSING) {
                // First time this row is bound — enqueue detail request
                mSubscriptionStatus.set(position, SubscriptionStatus.LOADING);
            }

            if (mSubscriptionStatus.get(position) != SubscriptionStatus.LOADED) {
                title.setText("Loading…");
                channel.setText("");
                drag.setVisibility(mInReorderMode ? View.VISIBLE : View.GONE);
                channel.setVisibility(mInReorderMode ? View.GONE : View.VISIBLE);
                // keep the rest of your click/long-click wiring here
                return;
            }

            // ====== BIND LOADED DATA ======
            final JsonNode item = mSubscriptionData.get(position);
            title.setText(Utils.stripQuotes(item.path("title").asText()));
            // channel.setText(...) if you have it in the item
            drag.setVisibility(mInReorderMode ? View.VISIBLE : View.GONE);
            channel.setVisibility(mInReorderMode ? View.GONE : View.VISIBLE);

            // Clicks
            h.itemView.setOnClickListener(v -> {
                if (item == null) return;
                final String collectionId = item.path("idSetSource").path("collectionId").asText();
                if (collectionId == null || collectionId.isEmpty()) return;
                Intent intent = new Intent(SeasonPass.this, ExploreTabs.class);
                intent.putExtra("collectionId", collectionId);
                startActivityForResult(intent, 1);
            });

            // Long clicks
            h.itemView.setOnLongClickListener(v -> {
                mLongClickPosition = h.getBindingAdapterPosition();
                // invoke your dialog code path (same as before)
                return onItemLongClick(null, v, mLongClickPosition, getItemId(h.getBindingAdapterPosition()));
            });

            // Start drag from handle only in reorder mode
            drag.setOnTouchListener((v, e) -> {
                if (mInReorderMode && e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    mItemTouchHelper.startDrag(h);
                }
                return false;
            });
        }

        public boolean onItemLongClick(AdapterView<?> parent, View view,
                                       int position, long id) {
            final JsonNode sub = mSubscriptionData.get(position);
            final String subType = sub.path("idSetSource").path("type").asText();
            if ("wishListSource".equals(subType)) {
                return false;
            }

            mLongClickPosition = position;

            final ArrayList<String> choices = new ArrayList<String>();
            choices.add(Explore.RecordActions.SP_MODIFY.toString());
            choices.add(Explore.RecordActions.SP_CANCEL.toString());
//            final ArrayAdapter<String> choicesAdapter =
//                    new ArrayAdapter<String>(this, R.layout.select_dialog_item,
//                            choices);

//            Builder dialogBuilder = new AlertDialog.Builder(this);
//            dialogBuilder.setTitle("Operation?");
//            dialogBuilder.setAdapter(choicesAdapter, this);
//            AlertDialog dialog = dialogBuilder.create();
//            dialog.show();

            return true;
        }

        @Override public int getItemCount() { return mSubscriptionData.size(); }

        @Override public long getItemId(int position) {
            // Use the subscription ID to provide stable IDs
            if (position < 0 || position >= mSubscriptionIds.size()) return RecyclerView.NO_ID;
            return mSubscriptionIds.get(position).hashCode();
        }

        final class VH extends RecyclerView.ViewHolder {
            VH(@NonNull View itemView) { super(itemView); }
        }
    }


    private void queueDetailFor() {
        if (mSubscriptionIds.isEmpty()) {
            return;
        }
        SubscriptionSearch req = new SubscriptionSearch(mSubscriptionIds);
        MindRpc.addRequest(req, mDetailCallback);
    }

    protected MindRpcResponseListener mDetailCallback =
            new MindRpcResponseListener() {
                public void onResponse(MindRpcResponse response) {
                    final JsonNode items = response.getBody().path("subscription");

                    ArrayList<Integer> slotMap =
                            mRequestSlotMap.get(response.getRpcId());

//                    for (int i = 0; i < items.size(); i++) {
                    int i = 0;
                    for (JsonNode item : items) {
//                        items.arr
//                        int pos = slotMap.get(i);
//                        JsonNode item = items.get(i);
                        mSubscriptionData.set(i, item);
                        mSubscriptionStatus.set(i++, SubscriptionStatus.LOADED);
                    }

                    mRequestSlotMap.remove(response.getRpcId());
                    mListAdapter.notifyDataSetChanged();
                }
            };
}
