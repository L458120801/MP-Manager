package io.github.abdurazaaqmohammed.features.files.network;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.io.OutputStream;

import io.github.abdurazaaqmohammed.utils.FileUtils;

/** Recursive transfers with fail-on-conflict and delete-source-only-after-success semantics. */
public final class RemoteTransfers {
    private RemoteTransfers() {}

    static final class DownloadPlan {
        final RemoteEntry entry;
        final List<DownloadPlan> children;
        final FileUtils.Measure measure;

        DownloadPlan(RemoteEntry entry, List<DownloadPlan> children, FileUtils.Measure measure) {
            this.entry = entry;
            this.children = children;
            this.measure = measure;
        }
    }

    static DownloadPlan prepareDownload(RemoteClient source, RemoteEntry entry,
                                        FileUtils.TransferListener listener) throws IOException {
        if (listener != null) listener.checkpoint();
        List<DownloadPlan> children = new ArrayList<>();
        FileUtils.Measure measure = new FileUtils.Measure();
        if (!entry.directory) {
            measure.files = 1;
            measure.bytes = entry.size;
            return new DownloadPlan(entry, children, measure);
        }
        for (RemoteEntry child : source.list(entry.path)) {
            requireChild(entry.path, child);
            DownloadPlan plan = prepareDownload(source, child, listener);
            children.add(plan);
            measure.files += plan.measure.files;
            measure.bytes += plan.measure.bytes;
        }
        return new DownloadPlan(entry, children, measure);
    }

    static void download(RemoteClient source, DownloadPlan plan, File destination,
                         FileUtils.TransferListener listener,
                         boolean countCompletedFiles) throws IOException {
        if (destination.exists()) throw new IOException("Destination already exists: " + destination.getName());
        if (plan.entry.directory) {
            if (!destination.mkdir()) throw new IOException("Cannot create destination folder");
            for (DownloadPlan child : plan.children)
                downloadPlannedFile(source, child, new File(destination, child.entry.name()), listener, countCompletedFiles);
            return;
        }
        downloadPlannedFile(source, plan, destination, listener, countCompletedFiles);
    }

    private static void downloadPlannedFile(RemoteClient source, DownloadPlan plan, File destination,
                                            FileUtils.TransferListener listener,
                                            boolean countCompletedFiles) throws IOException {
        if (listener != null) listener.checkpoint();
        if (plan.entry.directory) {
            if (!destination.mkdir()) throw new IOException("Cannot create destination folder");
            for (DownloadPlan child : plan.children)
                downloadPlannedFile(source, child, new File(destination, child.entry.name()), listener, countCompletedFiles);
            return;
        }
        if (listener != null) listener.onFile(new File("/network/" + plan.entry.path), destination);
        File temporary = File.createTempFile(".mp-download-", ".part", destination.getParentFile());
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                OutputStream monitored = listener == null ? output : new FilterOutputStream(output) {
                    @Override public void write(int value) throws IOException {
                        listener.checkpoint();
                        out.write(value);
                        listener.onBytes(1);
                    }

                    @Override public void write(byte[] buffer, int offset, int length) throws IOException {
                        listener.checkpoint();
                        out.write(buffer, offset, length);
                        listener.onBytes(length);
                    }
                };
                if (source instanceof SmbClient smb)
                    smb.download(plan.entry.path, monitored, listener);
                else source.download(plan.entry.path, monitored);
                output.getFD().sync();
            }
            if (plan.entry.size > 0 && temporary.length() != plan.entry.size)
                throw new IOException("Downloaded size does not match; source may have changed");
            java.nio.file.Files.move(temporary.toPath(), destination.toPath());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            temporary.delete();
        }
        if (plan.entry.modified > 0) destination.setLastModified(plan.entry.modified);
        if (countCompletedFiles && listener != null) listener.onFileComplete();
    }

    public static void download(RemoteClient source, RemoteEntry entry, File destination) throws IOException {
        if (destination.exists()) throw new IOException("Destination already exists: " + destination.getName());
        if (entry.directory) {
            if (!destination.mkdir()) throw new IOException("Cannot create destination folder");
            for (RemoteEntry child : source.list(entry.path)) {
                requireChild(entry.path, child);
                download(source, child, new File(destination, child.name()));
            }
        } else {
            File temporary = File.createTempFile(".mp-download-", ".part", destination.getParentFile());
            try {
                try (FileOutputStream output = new FileOutputStream(temporary)) {
                    source.download(entry.path, output);
                    output.getFD().sync();
                }
                if (entry.size > 0 && temporary.length() != entry.size)
                    throw new IOException("Downloaded size does not match; source may have changed");
                java.nio.file.Files.move(temporary.toPath(), destination.toPath());
            } finally {
                //noinspection ResultOfMethodCallIgnored
                temporary.delete();
            }
            if (entry.modified > 0) destination.setLastModified(entry.modified);
        }
    }

    public static void upload(RemoteClient destination, String parent, File source) throws IOException {
        upload(destination, parent, source, null);
    }

    static void upload(RemoteClient destination, String parent, File source,
                       FileUtils.TransferListener listener) throws IOException {
        if (java.nio.file.Files.isSymbolicLink(source.toPath())) throw new IOException("Symbolic links cannot be uploaded");
        String path = RemotePath.child(parent, source.getName());
        if (destination.stat(path) != null) throw new IOException("Destination already exists: " + source.getName());
        if (source.isDirectory()) {
            File[] children = source.listFiles();
            if (children == null) throw new IOException("Cannot read source folder");
            destination.mkdir(path);
            for (File child : children) upload(destination, path, child, listener);
            return;
        }
        if (listener == null) {
            try (FileInputStream input = new FileInputStream(source)) {
                destination.upload(path, input, source.length());
            }
            return;
        }
        listener.onFile(source, new File("/network/" + path));
        try (InputStream input = new FilterInputStream(new FileInputStream(source)) {
            @Override public int read() throws IOException {
                listener.checkpoint();
                int value = super.read();
                if (value >= 0) listener.onBytes(1);
                return value;
            }

            @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                listener.checkpoint();
                int count = super.read(buffer, offset, length);
                if (count > 0) listener.onBytes(count);
                return count;
            }
        }) {
            destination.upload(path, input, source.length());
        }
        listener.onFileComplete();
    }

    public static void moveToLocal(RemoteClient source, RemoteEntry entry, File destination) throws IOException {
        download(source, entry, destination);
        source.delete(entry.path, entry.directory);
    }

    static void deleteMovedSources(List<RemoteFile> sources, FileUtils.TransferListener listener) throws IOException {
        for (RemoteFile source : sources) {
            if (source.connection.client instanceof SmbClient smb) {
                smb.delete(source.entry.path, source.isDirectory(), listener);
                continue;
            }
            if (listener != null) listener.checkpoint();
            source.connection.client.delete(source.entry.path, source.isDirectory());
        }
    }

    static void requireChild(String directory, RemoteEntry child) throws IOException {
        if (child.path.equals(directory) || !RemotePath.parent(child.path).equals(directory))
            throw new IOException("Server returned a child outside the requested directory");
        RemotePath.validateName(child.name());
    }
}
