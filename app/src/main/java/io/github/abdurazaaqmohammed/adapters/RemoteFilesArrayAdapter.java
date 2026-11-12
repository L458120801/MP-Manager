package io.github.abdurazaaqmohammed.adapters;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.MaterialColors;

import org.apache.commons.io.FilenameUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.github.abdurazaaqmohammed.MPManager.MainActivity;
import io.github.abdurazaaqmohammed.MPManager.R;
import io.github.abdurazaaqmohammed.adapters.main.ApkManifestEditor;
import io.github.abdurazaaqmohammed.adapters.main.ApkToolsHandler;
import io.github.abdurazaaqmohammed.adapters.main.ChecksumDialogs;
import io.github.abdurazaaqmohammed.adapters.main.FileIconLoader;
import io.github.abdurazaaqmohammed.adapters.main.FileMenuOrder;
import io.github.abdurazaaqmohammed.adapters.main.FileRowStyle;
import io.github.abdurazaaqmohammed.features.files.FileOpener;
import io.github.abdurazaaqmohammed.features.files.network.NetworkFileMenu;
import io.github.abdurazaaqmohammed.features.files.network.RemoteFile;
import io.github.abdurazaaqmohammed.features.files.network.RemotePath;
import io.github.abdurazaaqmohammed.features.files.network.SmbClient;
import io.github.abdurazaaqmohammed.listeners.SwipeTouchListener;
import io.github.abdurazaaqmohammed.ui.UiFields;
import io.github.abdurazaaqmohammed.utils.FileSize;

public final class RemoteFilesArrayAdapter extends RecyclerView.Adapter<RemoteFilesArrayAdapter.Holder>
        implements MultiSelectableAdapter {
    private static final int VIEW_TYPE = 0x4e4554;
    private final MainActivity activity;
    private final boolean pane1;
    public final RemoteFile folder;
    public final RemoteFile[] files;
    private final FileRowStyle rowStyle;
    private final FileIconLoader icons;
    private boolean multiSelectMode;
    public final Set<Integer> selectedPositions = new HashSet<>();
    private Integer rangeStartPosition;

    public RemoteFilesArrayAdapter(MainActivity activity, RemoteFile folder, RemoteFile[] files, boolean pane1) {
        this.activity = activity;
        this.folder = folder;
        this.files = files;
        this.pane1 = pane1;
        rowStyle = new FileRowStyle(activity);
        icons = new FileIconLoader(activity, false, true);
    }

    @Override public boolean supportsMultiSelect() { return folder.connection.client instanceof SmbClient; }
    @Override public boolean isMultiSelectMode() { return multiSelectMode; }

    public void goUp() {
        RemoteFile parent = folder.getParentFile();
        if (parent != null) activity.loadFolderInPane(parent, pane1, true);
        else activity.loadFolderInPane(android.os.Environment.getExternalStorageDirectory(), pane1, true);
    }

    @Override public int getItemCount() { return files.length + 1; }
    @Override public int getItemViewType(int position) { return supportsMultiSelect() ? VIEW_TYPE + 1 : VIEW_TYPE; }

    public static final class Holder extends RecyclerView.ViewHolder {
        final TextView name, detail;
        final ImageView icon, badge;
        View.OnClickListener boundClickListener;
        View.OnLongClickListener boundLongClickListener;
        SwipeTouchListener touchListener;
        int lastBoundPosition = RecyclerView.NO_POSITION;
        int appliedBackgroundColor = -2;

        Holder(View view) {
            super(view);
            name = view.findViewById(R.id.fileName);
            detail = view.findViewById(R.id.fileDate);
            icon = view.findViewById(R.id.fileIcon);
            badge = view.findViewById(R.id.appBadge);
        }
    }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        Holder holder = new Holder(LayoutInflater.from(activity).inflate(R.layout.list_file, parent, false));
        if (supportsMultiSelect()) {
            holder.touchListener = new SwipeTouchListener(activity,
                    v -> { if (holder.boundClickListener != null) holder.boundClickListener.onClick(v); },
                    v -> holder.boundLongClickListener != null && holder.boundLongClickListener.onLongClick(v),
                    () -> {
                        int live = holder.getBindingAdapterPosition();
                        return live != RecyclerView.NO_POSITION ? live : holder.lastBoundPosition;
                    }, this, pane1 ? 1 : 2);
            holder.itemView.setOnTouchListener(holder.touchListener);
        }
        return holder;
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        if (holder.touchListener != null) holder.touchListener.rebind(this, pane1 ? 1 : 2);
        holder.lastBoundPosition = position;
        rowStyle.apply(holder.name, holder.icon);
        holder.badge.setVisibility(View.GONE);
        holder.badge.setImageDrawable(null);
        holder.badge.setTag(null);
        if (position == 0) {
            holder.name.setText("..");
            holder.name.setAlpha(1f);
            holder.icon.setAlpha(1f);
            icons.setupMetadataView("..", true, 0, 0, true, holder.icon, holder.detail);
            holder.boundClickListener = v -> { activity.setCurrentPane(pane1 ? 1 : 2); goUp(); };
            holder.boundLongClickListener = null;
            if (holder.touchListener == null) {
                holder.itemView.setOnClickListener(holder.boundClickListener);
                holder.itemView.setOnLongClickListener(null);
            }
            return;
        }
        RemoteFile file = files[position - 1];
        holder.name.setText(file.getName());
        icons.setupMetadataView(file.getName(), file.isDirectory(), file.length(), file.lastModified(),
                false, holder.icon, holder.detail);
        holder.name.setAlpha(file.isHidden() ? 0.55f : 1f);
        holder.icon.setAlpha(file.isHidden() ? 0.55f : 1f);
        int background = selectedPositions.contains(position)
                ? MaterialColors.getColor(holder.itemView,
                com.google.android.material.R.attr.colorSecondaryContainer, Color.TRANSPARENT)
                : Color.TRANSPARENT;
        if (holder.appliedBackgroundColor != background) {
            holder.itemView.setBackgroundColor(background);
            holder.appliedBackgroundColor = background;
        }
        holder.boundClickListener = v -> {
            activity.setCurrentPane(pane1 ? 1 : 2);
            if (file.isDirectory()) activity.loadFolderInPane(file, pane1, true);
            else activity.getNetwork().open(file, opened -> openLocal(opened, file.getName()));
        };
        holder.boundLongClickListener = v -> {
            activity.setCurrentPane(pane1 ? 1 : 2);
            List<RemoteFile> targets = new ArrayList<>();
            if (selectedPositions.isEmpty()) targets.add(file);
            else for (int selected : selectedPositions) targets.add(files[selected - 1]);
            boolean multi = targets.size() > 1;
            RemoteFile target = targets.get(0);
            boolean shareRoot = folder.connection.client instanceof SmbClient
                    && targets.stream().anyMatch(item -> item.entry.path.indexOf('/') == -1);
            Set<String> disabled = new HashSet<>();
            if (!activity.getNetwork().canCopyOut(targets, pane1)) {
                disabled.add(FileMenuOrder.COPY);
                disabled.add(FileMenuOrder.MOVE);
            }
            if (multi) {
                disabled.add(FileMenuOrder.RENAME);
                disabled.add(FileMenuOrder.PROPERTIES);
            }
            if (shareRoot) {
                disabled.add(FileMenuOrder.MOVE);
                disabled.add(FileMenuOrder.RENAME);
                disabled.add(FileMenuOrder.DELETE);
            }
            NetworkFileMenu.show(activity, multi ? activity.getString(R.string.multi_select)
                    : file.getName(), pane1, disabled, action -> {
                switch (action) {
                    case FileMenuOrder.COPY -> activity.getNetwork().copyOut(targets, pane1, false);
                    case FileMenuOrder.MOVE -> activity.getNetwork().copyOut(targets, pane1, true);
                    case FileMenuOrder.RENAME -> rename(target);
                    case FileMenuOrder.DELETE -> activity.dialogUtil.getDialogBuilder()
                            .setTitle(R.string.delete)
                            .setMessage(R.string.network_recursive_delete)
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(android.R.string.ok, (d, w) -> activity.getNetwork().runOperation(
                                    activity.getString(R.string.delete), () -> {
                                        for (RemoteFile selected : targets)
                                            selected.connection.client.delete(selected.entry.path, selected.isDirectory());
                                    }, activity::refreshAllPanes)).show();
                    case FileMenuOrder.PROPERTIES -> activity.dialogUtil.getDialogBuilder().setTitle(R.string.properties)
                            .setMessage(target.displayPath() + "\n" + (target.isDirectory() ? activity.getString(R.string.folder)
                                    : FileSize.getHumanReadableFileSize(target.length())))
                            .setPositiveButton(android.R.string.ok, null).show();
                }
            });
            return true;
        };
        if (holder.touchListener == null) {
            holder.itemView.setOnClickListener(holder.boundClickListener);
            holder.itemView.setOnLongClickListener(holder.boundLongClickListener);
        }
    }

    @Override public void handleSwipe(int position) {
        if (position <= 0 || position >= getItemCount()) return;
        activity.setCurrentPane(pane1 ? 1 : 2);
        if (multiSelectMode) {
            if (rangeStartPosition == null) {
                selectedPositions.add(position);
                rangeStartPosition = position;
            } else {
                for (int i = Math.min(rangeStartPosition, position); i <= Math.max(rangeStartPosition, position); i++)
                    selectedPositions.add(i);
                rangeStartPosition = null;
            }
        } else {
            multiSelectMode = true;
            rangeStartPosition = position;
            selectedPositions.add(position);
            activity.setMultiSelectModeUI(true);
        }
        notifyDataSetChanged();
    }

    @Override public void handleMultiSelect(int position) {
        if (position <= 0 || position >= getItemCount()) return;
        if (!selectedPositions.remove(position)) selectedPositions.add(position);
        if (selectedPositions.isEmpty()) {
            multiSelectMode = false;
            rangeStartPosition = null;
            activity.setMultiSelectModeUI(false);
        }
        notifyDataSetChanged();
    }

    @Override public void selectAll() {
        multiSelectMode = true;
        for (int i = 1; i < getItemCount(); i++) selectedPositions.add(i);
        notifyDataSetChanged();
    }

    @Override public void invertSelection() {
        multiSelectMode = true;
        for (int i = 1; i < getItemCount(); i++) {
            if (!selectedPositions.remove(i)) selectedPositions.add(i);
        }
        notifyDataSetChanged();
    }

    @Override public void selectSameType() {
        if (selectedPositions.isEmpty()) return;
        RemoteFile reference = files[selectedPositions.iterator().next() - 1];
        String extension = FilenameUtils.getExtension(reference.getName()).toLowerCase(Locale.ROOT);
        selectedPositions.clear();
        for (int i = 1; i < getItemCount(); i++) {
            RemoteFile candidate = files[i - 1];
            if (reference.isDirectory() ? candidate.isDirectory()
                    : candidate.isFile() && FilenameUtils.getExtension(candidate.getName())
                    .toLowerCase(Locale.ROOT).equals(extension)) selectedPositions.add(i);
        }
        multiSelectMode = true;
        notifyDataSetChanged();
    }

    @Override public void clearSelection() {
        selectedPositions.clear();
        multiSelectMode = false;
        rangeStartPosition = null;
        activity.setMultiSelectModeUI(false);
        notifyDataSetChanged();
    }

    @Override public void exitMultiSelectMode() {
        clearSelection();
        activity.setCurrentPane(pane1 ? 1 : 2);
    }

    private void rename(RemoteFile file) {
        EditText name = new EditText(activity);
        name.setSingleLine(true);
        name.setText(file.getName());
        activity.dialogUtil.getDialogBuilder().setTitle(R.string.rename)
                .setView(UiFields.wrap(activity, name, null, 16)).setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> activity.getNetwork().runOperation(activity.getString(R.string.rename),
                        () -> file.connection.client.rename(file.entry.path, RemotePath.child(folder.entry.path, name.getText().toString())),
                        activity::refreshAllPanes)).show();
    }

    private void openLocal(File file, String name) {
        ApkManifestEditor manifest = new ApkManifestEditor(activity, activity.dialogUtil, activity.uiHelper);
        ApkToolsHandler tools = new ApkToolsHandler(activity, activity.dialogUtil, activity.uiHelper, pane1, manifest);
        new FileOpener(activity, activity.dialogUtil, pane1, tools,
                new ChecksumDialogs(activity, activity.dialogUtil), null).handleFileClick(file, name);
    }
}
