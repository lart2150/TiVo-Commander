
package com.arantius.tivocommander;

import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
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
import com.arantius.tivocommander.rpc.response.MindRpcResponseListener;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;

public class SeasonPass extends AppCompatActivity
        implements AdapterView.OnItemLongClickListener, DialogInterface.OnClickListener {

    // ====== data you already had ======
    protected enum SubscriptionStatus { LOADED, LOADING, MISSING; }
    protected static final int MAX_REQUEST_BATCH = 5;
    protected boolean mInReorderMode = false;
    protected int mLongClickPosition;

    protected final ArrayList<JsonNode> mSubscriptionData = new ArrayList<>();
    protected final ArrayList<String>   mSubscriptionIds   = new ArrayList<>();
    protected final ArrayList<String>   mSubscriptionIdsBeforeReorder = new ArrayList<>();
    protected final ArrayList<SubscriptionStatus> mSubscriptionStatus  = new ArrayList<>();
    protected final android.util.SparseArray<ArrayList<Integer>> mRequestSlotMap =
            new android.util.SparseArray<>();

    // ====== UI & adapter ======
    private RecyclerView mRecyclerView;
    private SubscriptionAdapter mListAdapter;
    private ItemTouchHelper mItemTouchHelper;

    // ====== clicks ======
    private final AdapterView.OnItemClickListener mOnClickListener =
            (parent, view, position, id) -> {
                final JsonNode item = mSubscriptionData.get(position);
                if (item == null) return;
                final String collectionId = item.path("idSetSource").path("collectionId").asText();
                if ("".equals(collectionId)) return;

                Intent intent = new Intent(SeasonPass.this, ExploreTabs.class);
                intent.putExtra("collectionId", collectionId);
                startActivityForResult(intent, 1);
            };

    // ====== RPC callback you already had ======
    protected final MindRpcResponseListener mDetailCallback = response -> {
        final JsonNode items = response.getBody().path("subscription");
        ArrayList<Integer> slotMap = mRequestSlotMap.get(response.getRpcId());
        for (int i = 0; i < items.size(); i++) {
            int pos = slotMap.get(i);
            JsonNode item = items.get(i);
            mSubscriptionData.set(pos, item);
            mSubscriptionStatus.set(pos, SubscriptionStatus.LOADED);
        }
        mRequestSlotMap.remove(response.getRpcId());
        mListAdapter.notifyDataSetChanged();
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (MindRpc.init(this, null)) return;

//        requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
        Utils.activateHomeButton(this);
        setTitle("Season Pass Manager");
        setContentView(R.layout.list_season_pass);

        // 1) RecyclerView
        mRecyclerView = findViewById(R.id.season_pass_list);
        mRecyclerView.setLayoutManager(new LinearLayoutManager(this));

        // 2) Adapter (RecyclerView version of your old ArrayAdapter)
        mListAdapter = new SubscriptionAdapter();
        mRecyclerView.setAdapter(mListAdapter);

        // 3) ItemTouchHelper for drag to reorder
        ItemTouchHelper.SimpleCallback dragCallback = new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0 /* no swipe */) {

            @Override
            public boolean isLongPressDragEnabled() {
                // We show a drag handle; start programmatically from that view
                return false;
            }

            @Override
            public boolean onMove(@NonNull RecyclerView rv,
                                  @NonNull RecyclerView.ViewHolder from,
                                  @NonNull RecyclerView.ViewHolder to) {
                int fromPos = from.getBindingAdapterPosition();
                int toPos   = to.getBindingAdapterPosition();

                // Keep your data structures in sync (like your old DropListener)
                Collections.swap(mSubscriptionData, fromPos, toPos);
                Collections.swap(mSubscriptionStatus, fromPos, toPos);
                Collections.swap(mSubscriptionIds, fromPos, toPos);
                mListAdapter.notifyItemMoved(fromPos, toPos);
                return true;
            }

            @Override public void onSwiped(@NonNull RecyclerView.ViewHolder v, int dir) { /* no-op */ }
        };
        mItemTouchHelper = new ItemTouchHelper(dragCallback);
        mItemTouchHelper.attachToRecyclerView(mRecyclerView);

        startRequest();
    }

    // === Your old startRequest() unchanged, except no ListActivity bits ===
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
        };
        MindRpc.addRequest(new SubscriptionSearch(), idSequenceCallback);
    }

    // === Long-click dialog unchanged ===
    @Override public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
        // same code you already had...
        return true;
    }

    // === Buttons ===
    public void reorderEnable(View v) {
        // Load missing, then enable dragging
        // (same as before, but when done set mInReorderMode=true and notify)
        // Also allow starting a drag via the handle in onBindViewHolder:
        // holder.dragHandle.setOnTouchListener(...startDrag(holder)...)
        // existing code mostly unchanged; omitted for brevity
        mInReorderMode = true;
        mListAdapter.notifyDataSetChanged();
        findViewById(R.id.reorder_enable).setVisibility(View.GONE);
        findViewById(R.id.reorder_apply).setVisibility(View.VISIBLE);
    }

    public void reorderApply(View v) {
        // Same logic you had, then:
        mInReorderMode = false;
        mListAdapter.notifyDataSetChanged();
        findViewById(R.id.reorder_enable).setVisibility(View.VISIBLE);
        findViewById(R.id.reorder_apply).setVisibility(View.GONE);
    }

    // === Dialog onClick unchanged ===
    @Override public void onClick(DialogInterface dialog, int which) {
        // same as before
    }

    // ===== RecyclerView adapter =====
    private final class SubscriptionAdapter extends RecyclerView.Adapter<SubscriptionAdapter.VH> {
        private final ColorDrawable mBlankLogoDrawable;

        SubscriptionAdapter() {
            mBlankLogoDrawable = new ColorDrawable(0x00000000);
            Rect r = new Rect(0, 0, 65, 55);
            mBlankLogoDrawable.setBounds(r);
        }

        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            // Reuse your existing row layouts
            LayoutInflater vi = LayoutInflater.from(parent.getContext());
            View v = vi.inflate(R.layout.item_season_pass, parent, false);
            return new VH(v);
        }

        @Override public void onBindViewHolder(@NonNull VH h, int position) {
            if (mSubscriptionStatus.get(position) != SubscriptionStatus.LOADED) {
                // Show placeholder text so the row consumes height
                TextView title = h.itemView.findViewById(R.id.show_title);
                TextView index = h.itemView.findViewById(R.id.index);
                ImageView dragHandle = h.itemView.findViewById(R.id.drag_handle);

                index.setText(String.valueOf(position + 1));
                title.setText("Loading…");
                dragHandle.setVisibility(View.GONE);
                // You can also show a small ProgressBar in item_season_pass if you add one.
                return;
            }

            final JsonNode item = mSubscriptionData.get(position);
            ((TextView) h.itemView.findViewById(R.id.index)).setText(Integer.toString(position + 1));
            ((TextView) h.itemView.findViewById(R.id.show_title))
                    .setText(Utils.stripQuotes(item.path("title").asText()));

            // ... (the rest of your original getView() binding, unchanged) ...

            final ImageView dragHandle = h.itemView.findViewById(R.id.drag_handle);
            final TextView channelView = h.itemView.findViewById(R.id.show_channel);

            if (mInReorderMode) {
                dragHandle.setVisibility(View.VISIBLE);
                channelView.setVisibility(View.GONE);

                // Start drag when user touches the handle
                dragHandle.setOnTouchListener((v, e) -> {
                    if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        mItemTouchHelper.startDrag(h);
                    }
                    return false;
                });
            } else {
                dragHandle.setVisibility(View.GONE);
                channelView.setVisibility(View.VISIBLE);
                // ... (logo loading as in your original code) ...
            }

            // Clicks (replace ListView's OnItemClickListener)
            h.itemView.setOnClickListener(v -> {
                AdapterView.OnItemClickListener l = mOnClickListener;
                if (l != null) l.onItemClick(null, v, h.getBindingAdapterPosition(), h.getItemId());
            });

            // Long clicks
            h.itemView.setOnLongClickListener(v -> {
                mLongClickPosition = h.getBindingAdapterPosition();
                // show the same dialog as before
                // (call the same code path you used in onItemLongClick)
                return onItemLongClick(null, v, mLongClickPosition, getItemId(h.getBindingAdapterPosition()));
            });
        }

        @Override public int getItemCount() { return mSubscriptionData.size(); }

        final class VH extends RecyclerView.ViewHolder {
            VH(@NonNull View itemView) { super(itemView); setIsRecyclable(true); }
        }
    }
}
