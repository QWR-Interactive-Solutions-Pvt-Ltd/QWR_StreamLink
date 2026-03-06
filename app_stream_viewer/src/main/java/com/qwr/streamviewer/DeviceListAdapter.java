package com.qwr.streamviewer;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DeviceListAdapter extends ListAdapter<DeviceItem, DeviceListAdapter.ViewHolder> {

    private final DeviceItemClickListener listener;
    private final PreviewToggleListener previewListener;
    private final Set<String> previewEnabled = new HashSet<>();
    private final Map<String, Bitmap> previews = new HashMap<>();

    public interface PreviewToggleListener {
        void onPreviewToggled(String ip, boolean enabled);
    }

    private static final DiffUtil.ItemCallback<DeviceItem> DIFF_CALLBACK =
            new DiffUtil.ItemCallback<DeviceItem>() {
                @Override
                public boolean areItemsTheSame(@NonNull DeviceItem oldItem, @NonNull DeviceItem newItem) {
                    return oldItem.getAddressIp().equals(newItem.getAddressIp());
                }

                @Override
                public boolean areContentsTheSame(@NonNull DeviceItem oldItem, @NonNull DeviceItem newItem) {
                    return oldItem.getName().equals(newItem.getName())
                            && oldItem.getSerialNumber().equals(newItem.getSerialNumber())
                            && oldItem.getStatus() == newItem.getStatus();
                }
            };

    public DeviceListAdapter(DeviceItemClickListener listener, PreviewToggleListener previewListener) {
        super(DIFF_CALLBACK);
        this.listener = listener;
        this.previewListener = previewListener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_device_card, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        DeviceItem item = getItem(position);
        String ip = item.getAddressIp();
        boolean showPreview = previewEnabled.contains(ip);
        Bitmap preview = previews.get(ip);
        holder.bind(item, showPreview, preview, listener, previewListener, this);
    }

    public void addDevice(DeviceItem item) {
        java.util.List<DeviceItem> current = new java.util.ArrayList<>(getCurrentList());
        for (DeviceItem existing : current) {
            if (existing.getAddressIp().equals(item.getAddressIp())) {
                return;
            }
        }
        current.add(item);
        submitList(current);
    }

    public void updateDeviceStatus(String ip, DeviceStatus status) {
        java.util.List<DeviceItem> current = getCurrentList();
        for (int i = 0; i < current.size(); i++) {
            if (current.get(i).getAddressIp().equals(ip)
                    && current.get(i).getStatus() != status) {
                java.util.List<DeviceItem> updated = new java.util.ArrayList<>(current);
                DeviceItem old = updated.get(i);
                updated.set(i, new DeviceItem(old.getName(), old.getAddressIp(),
                        old.getSerialNumber(), status));
                submitList(updated);
                return;
            }
        }
    }

    public void clear() {
        previewEnabled.clear();
        recycleAllPreviews();
        submitList(null);
    }

    public void togglePreview(String ip) {
        if (previewEnabled.contains(ip)) {
            previewEnabled.remove(ip);
            Bitmap old = previews.remove(ip);
            if (old != null && !old.isRecycled()) old.recycle();
        } else {
            previewEnabled.add(ip);
        }
        notifyItemChanged(findPositionByIp(ip));
    }

    public boolean isPreviewEnabled(String ip) {
        return previewEnabled.contains(ip);
    }

    public void setPreview(String ip, Bitmap bitmap) {
        Bitmap old = previews.put(ip, bitmap);
        if (old != null && old != bitmap && !old.isRecycled()) old.recycle();
        int pos = findPositionByIp(ip);
        if (pos >= 0) {
            notifyItemChanged(pos, "preview_update");
        }
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position, @NonNull java.util.List<Object> payloads) {
        if (!payloads.isEmpty() && "preview_update".equals(payloads.get(0))) {
            DeviceItem item = getItem(position);
            Bitmap preview = previews.get(item.getAddressIp());
            holder.updatePreview(preview);
        } else {
            super.onBindViewHolder(holder, position, payloads);
        }
    }

    public void recycleAllPreviews() {
        for (Bitmap bmp : previews.values()) {
            if (bmp != null && !bmp.isRecycled()) bmp.recycle();
        }
        previews.clear();
    }

    private int findPositionByIp(String ip) {
        for (int i = 0; i < getCurrentList().size(); i++) {
            if (getCurrentList().get(i).getAddressIp().equals(ip)) return i;
        }
        return -1;
    }

    static class ViewHolder extends RecyclerView.ViewHolder {

        private final ImageView headsetIcon;
        private final View statusDot;
        private final TextView deviceName;
        private final TextView deviceSerial;
        private final TextView deviceIp;
        private final TextView btnPreview;
        private final FrameLayout previewContainer;
        private final ImageView previewImage;
        private final ProgressBar previewLoading;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            headsetIcon = itemView.findViewById(R.id.headsetIcon);
            statusDot = itemView.findViewById(R.id.statusDot);
            deviceName = itemView.findViewById(R.id.deviceName);
            deviceSerial = itemView.findViewById(R.id.deviceSerial);
            deviceIp = itemView.findViewById(R.id.deviceIp);
            btnPreview = itemView.findViewById(R.id.btnPreview);
            previewContainer = itemView.findViewById(R.id.previewContainer);
            previewImage = itemView.findViewById(R.id.previewImage);
            previewLoading = itemView.findViewById(R.id.previewLoading);
        }

        void bind(DeviceItem item, boolean showPreview, Bitmap preview,
                  DeviceItemClickListener clickListener,
                  PreviewToggleListener previewListener,
                  DeviceListAdapter adapter) {

            headsetIcon.setImageResource(HeadsetIconConfig.getIconFor(item.getName()));
            deviceName.setText(item.getName());
            deviceSerial.setText(itemView.getContext().getString(R.string.serial_label, item.getSerialNumber()));
            deviceIp.setText(item.getAddressIp());

            // Status dot
            switch (item.getStatus()) {
                case CONNECTED:
                    statusDot.setBackgroundResource(R.drawable.bg_status_connected);
                    break;
                case DISCONNECTED:
                    statusDot.setBackgroundResource(R.drawable.bg_status_disconnected);
                    break;
                case IDLE:
                default:
                    statusDot.setBackgroundResource(R.drawable.bg_status_idle);
                    break;
            }

            // Preview toggle
            btnPreview.setText(showPreview ? R.string.hide_preview : R.string.show_preview);
            btnPreview.setOnClickListener(v -> {
                previewListener.onPreviewToggled(item.getAddressIp(), !showPreview);
            });

            // Preview area
            if (showPreview) {
                previewContainer.setVisibility(View.VISIBLE);
                if (preview != null && !preview.isRecycled()) {
                    previewImage.setImageBitmap(preview);
                    previewLoading.setVisibility(View.GONE);
                } else {
                    previewImage.setImageBitmap(null);
                    previewLoading.setVisibility(View.VISIBLE);
                }
            } else {
                previewContainer.setVisibility(View.GONE);
            }

            // Card click → stream view
            itemView.setOnClickListener(v -> clickListener.onDeviceClicked(item));
        }

        void updatePreview(Bitmap preview) {
            if (previewContainer.getVisibility() == View.VISIBLE && preview != null && !preview.isRecycled()) {
                previewImage.setImageBitmap(preview);
                previewLoading.setVisibility(View.GONE);
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Click callback for device cards
// ---------------------------------------------------------------------------
interface DeviceItemClickListener {
    void onDeviceClicked(DeviceItem item);
}

// ---------------------------------------------------------------------------
// Uniform grid spacing decoration for the device RecyclerView
// ---------------------------------------------------------------------------
class GridSpacingDecoration extends RecyclerView.ItemDecoration {

    private final int spanCount;
    private final int spacing;

    GridSpacingDecoration(int spanCount, int spacing) {
        this.spanCount = spanCount;
        this.spacing = spacing;
    }

    @Override
    public void getItemOffsets(@NonNull android.graphics.Rect outRect, @NonNull View view,
                               @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        int position = parent.getChildAdapterPosition(view);
        int column = position % spanCount;
        outRect.left = spacing - column * spacing / spanCount;
        outRect.right = (column + 1) * spacing / spanCount;
        outRect.bottom = spacing;
    }
}

// ---------------------------------------------------------------------------
// Maps device name prefixes to headset icon drawables.
//
// HOW TO ADD A NEW HEADSET:
//   1. Add a drawable to res/drawable/ (e.g. ic_headset_mynewdevice.xml)
//   2. Add a new entry() line below with the name prefix and your drawable.
//
// Matching is case-insensitive; first match wins. The last entry (empty prefix)
// is the fallback default.
// ---------------------------------------------------------------------------
class HeadsetIconConfig {

    static class Entry {
        final String namePrefix;
        final int iconResId;

        Entry(String namePrefix, int iconResId) {
            this.namePrefix = namePrefix;
            this.iconResId = iconResId;
        }
    }

    // Add new headset entries here
    static final List<Entry> ENTRIES = Arrays.asList(
            entry("SXR",       R.drawable.ic_headset_sxr),
            entry("VRone", R.drawable.ic_headset_vrone_edu),
            entry("",          R.drawable.ic_device_headset)   // default — must be last
    );

    static int getIconFor(String deviceName) {
        if (deviceName == null) deviceName = "";
        String lower = deviceName.toLowerCase();
        for (Entry e : ENTRIES) {
            if (lower.startsWith(e.namePrefix.toLowerCase())) {
                return e.iconResId;
            }
        }
        return R.drawable.ic_device_headset;
    }

    private static Entry entry(String prefix, int resId) {
        return new Entry(prefix, resId);
    }
}
