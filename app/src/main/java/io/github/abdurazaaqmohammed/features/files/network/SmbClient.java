package io.github.abdurazaaqmohammed.features.files.network;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.mserref.NtStatus;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileAllInformation;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.msfscc.fileinformation.FileStandardInformation;
import com.hierynomus.mssmb2.SMB2Dialect;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2CreateOptions;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.mssmb2.SMBApiException;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskEntry;
import com.hierynomus.smbj.share.DiskShare;

import io.github.abdurazaaqmohammed.utils.FileUtils.TransferListener;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class SmbClient implements RemoteClient {
    private static final int SINGLE_CONNECTION_CHUNK_SIZE = 8 * 1024 * 1024;
    private static final int SINGLE_CONNECTION_MAX_IN_FLIGHT = 10;
    private static final int MULTI_CONNECTION_CHUNK_SIZE = 1024 * 1024;
    private static final int MULTI_CONNECTION_COUNT = 6;
    private static final long MULTI_CONNECTION_THRESHOLD = 32L * 1024 * 1024;

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String domain;
    private SMBClient client;
    private Connection connection;
    private Session session;
    private final Map<String, DiskShare> shares = new HashMap<>();
    private List<String> serverShares;
    private boolean closed;

    public SmbClient(String host, int port, String username, String password, String domain) throws IOException {
        if (host.isEmpty() || host.contains("/") || host.contains("\\") || port < 1 || port > 65535)
            throw new IllegalArgumentException("Invalid SMB host or port");
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.domain = domain;
        reconnect();
    }

    private void reconnect() throws IOException {
        if (client != null) closeTransport();
        SmbConfig config = smbConfig();
        SMBClient replacement = new SMBClient(config);
        Connection connected;
        Session authenticated;
        try {
            connected = replacement.connect(host, port);
            authenticated = connected.authenticate(username.isEmpty() ? AuthenticationContext.anonymous()
                    : new AuthenticationContext(username, password.toCharArray(), domain));
        } catch (Exception e) {
            replacement.close();
            throw error(e);
        }
        client = replacement;
        connection = connected;
        session = authenticated;
        shares.clear();
        serverShares = null;
    }

    static SmbConfig smbConfig() {
        return SmbConfig.builder().withNegotiatedBufferSize()
                .withTransactBufferSize(64 * 1024)
                .withTimeout(60, TimeUnit.SECONDS)
                .withSocketFactory(new TimedSocketFactory(javax.net.SocketFactory.getDefault(), 20000))
                .withSoTimeout(20000)
                .withDialects(SMB2Dialect.SMB_2_1, SMB2Dialect.SMB_2_0_2)
                .withSigningEnabled(false).build();
    }

    private void ensureConnected() throws IOException {
        if (closed) throw new IOException("SMB connection is closed");
        if (!connection.isConnected() || shares.values().stream().anyMatch(share -> !share.isConnected())) reconnect();
    }

    private void closeTransport() {
        for (DiskShare share : shares.values()) try { share.close(); } catch (Exception ignored) {}
        shares.clear();
        serverShares = null;
        try { session.close(); } catch (Exception ignored) {}
        try { connection.close(); } catch (Exception ignored) {}
        client.close();
    }

    static String smbPath(String path) {
        String normalized = RemotePath.normalize(path);
        if (normalized.indexOf(':') >= 0) throw new IllegalArgumentException("SMB alternate data streams are not supported");
        return normalized.replace('/', '\\');
    }

    private static IOException error(Exception e) {
        return e instanceof IOException ? (IOException) e : new IOException("SMB: " + e.getMessage(), e);
    }

    private static <T> T await(Future<T> future) throws IOException {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("SMB transfer interrupted", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) throw error(cause);
            throw error(e);
        }
    }

    private static <T> T awaitRead(Future<T> future, TransferListener listener) throws IOException {
        if (listener == null) return await(future);
        while (true) {
            listener.checkpoint();
            try {
                return future.get(100, TimeUnit.MILLISECONDS);
            } catch (TimeoutException ignored) {
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("SMB transfer interrupted", e);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof Exception cause) throw error(cause);
                throw error(e);
            }
        }
    }

    private static void awaitWrite(PendingWrite write) throws IOException {
        if (await(write.future) != write.buffer.length) throw new IOException("SMB write completed with fewer bytes than supplied");
    }

    static boolean isMissing(long status) {
        return status == NtStatus.STATUS_NO_SUCH_FILE.getValue()
                || status == NtStatus.STATUS_OBJECT_NAME_NOT_FOUND.getValue()
                || status == NtStatus.STATUS_OBJECT_PATH_NOT_FOUND.getValue();
    }

    private List<String> serverShares() throws IOException {
        if (serverShares == null) serverShares = SmbShareEnumerator.enumerate(session, host);
        return serverShares;
    }

    private SharePath resolve(String path) throws IOException {
        String normalized = RemotePath.normalize(path);
        int slash = normalized.indexOf('/');
        String shareName = slash == -1 ? normalized : normalized.substring(0, slash);
        if (shareName.isEmpty() || !serverShares().contains(shareName)) throw new IOException("SMB share not found: " + shareName);
        DiskShare share = shares.get(shareName);
        if (share == null) {
            com.hierynomus.smbj.share.Share connected = session.connectShare(shareName);
            if (!(connected instanceof DiskShare)) {
                connected.close();
                throw new IOException("SMB location is not a disk share");
            }
            share = (DiskShare) connected;
            shares.put(shareName, share);
        }
        return new SharePath(shareName, share, slash == -1 ? "" : normalized.substring(slash + 1));
    }

    private SharePath resolveWritable(String path) throws IOException {
        String normalized = RemotePath.normalize(path);
        if (normalized.indexOf('/') == -1) throw new IOException("Select an SMB share before changing files");
        SharePath target = resolve(normalized);
        if (target.path.isEmpty()) throw new IOException("Select a folder inside the SMB share before changing files");
        return target;
    }

    @Override public synchronized List<RemoteEntry> list(String directory) throws IOException {
        try {
            ensureConnected();
            String normalized = RemotePath.normalize(directory);
            List<RemoteEntry> result = new ArrayList<>();
            if (normalized.isEmpty()) {
                for (String name : serverShares()) result.add(new RemoteEntry(name, true, 0, 0));
                return result;
            }
            SharePath target = resolve(normalized);
            for (FileIdBothDirectoryInformation entry : target.share.list(smbPath(target.path))) {
                String name = entry.getFileName();
                if (name.equals(".") || name.equals("..")) continue;
                if ((entry.getFileAttributes() & FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT.getValue()) != 0) continue;
                result.add(new RemoteEntry(RemotePath.child(normalized, name),
                        (entry.getFileAttributes() & FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue()) != 0,
                        entry.getEndOfFile(), entry.getLastWriteTime().toEpochMillis()));
            }
            return result;
        } catch (Exception e) { throw error(e); }
    }

    @Override public synchronized RemoteEntry stat(String path) throws IOException {
        ensureConnected();
        String normalized = RemotePath.normalize(path);
        if (normalized.isEmpty()) return new RemoteEntry("", true, 0, 0);
        if (normalized.indexOf('/') == -1) {
            for (String name : serverShares()) if (name.equals(normalized)) return new RemoteEntry(normalized, true, 0, 0);
            return null;
        }
        try {
            SharePath target = resolve(normalized);
            String smbPath = smbPath(target.path);
            if (!smbPath.isEmpty() && !target.share.fileExists(smbPath) && !target.share.folderExists(smbPath)) return null;
            FileAllInformation info = target.share.getFileInformation(smbPath);
            if ((info.getBasicInformation().getFileAttributes() & FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT.getValue()) != 0)
                throw new IOException("SMB reparse points are not supported");
            return new RemoteEntry(normalized, info.getStandardInformation().isDirectory(),
                    info.getStandardInformation().getEndOfFile(), info.getBasicInformation().getLastWriteTime().toEpochMillis());
        } catch (SMBApiException e) {
            if (isMissing(e.getStatusCode())) return null;
            throw error(e);
        } catch (Exception e) { throw error(e); }
    }

    @Override public void download(String path, OutputStream destination) throws IOException {
        download(path, destination, null);
    }

    synchronized void download(String path, OutputStream destination,
                               TransferListener listener) throws IOException {
        try {
            if (listener != null) listener.checkpoint();
            ensureConnected();
            SharePath target = resolve(path);
            try (com.hierynomus.smbj.share.File file = target.share.openFile(smbPath(target.path),
                    EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN, EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE))) {
                long fileSize = file.getFileInformation(FileStandardInformation.class).getEndOfFile();
                if (fileSize < MULTI_CONNECTION_THRESHOLD) {
                    downloadSingleConnection(file, fileSize, destination, listener);
                    return;
                }
                List<TransferChannel> channels = new ArrayList<>(MULTI_CONNECTION_COUNT - 1);
                try {
                    for (int lane = 1; lane < MULTI_CONNECTION_COUNT; lane++)
                        channels.add(openTransferChannel(target.shareName, target.path, false));
                    List<com.hierynomus.smbj.share.File> files = new ArrayList<>(MULTI_CONNECTION_COUNT);
                    files.add(file);
                    for (TransferChannel channel : channels) files.add(channel.file);
                    downloadMultiConnection(files, fileSize, destination, listener);
                } finally {
                    for (TransferChannel channel : channels) channel.close();
                }
            }
        } catch (Exception e) { throw error(e); }
    }

    private static void downloadSingleConnection(com.hierynomus.smbj.share.File file, long fileSize,
                                                  OutputStream destination,
                                                  TransferListener listener) throws IOException {
        ExecutorService executor = Executors.newFixedThreadPool(SINGLE_CONNECTION_MAX_IN_FLIGHT);
        ArrayDeque<Future<byte[]>> pendingReads = new ArrayDeque<>();
        try {
            for (long offset = 0; offset < fileSize;) {
                if (listener != null) listener.checkpoint();
                long readOffset = offset;
                int readSize = (int) Math.min(SINGLE_CONNECTION_CHUNK_SIZE, fileSize - readOffset);
                pendingReads.add(executor.submit(() -> readChunk(file, readOffset, readSize, listener)));
                offset += readSize;
                if (pendingReads.size() == SINGLE_CONNECTION_MAX_IN_FLIGHT) {
                    byte[] data = awaitRead(pendingReads.getFirst(), listener);
                    pendingReads.removeFirst();
                    destination.write(data);
                }
            }
            while (!pendingReads.isEmpty()) {
                byte[] data = awaitRead(pendingReads.getFirst(), listener);
                pendingReads.removeFirst();
                destination.write(data);
            }
        } finally {
            for (Future<byte[]> pendingRead : pendingReads) pendingRead.cancel(true);
            executor.shutdownNow();
        }
    }

    private static void downloadMultiConnection(List<com.hierynomus.smbj.share.File> files,
                                                long fileSize, OutputStream destination,
                                                TransferListener listener) throws IOException {
        ExecutorService executor = Executors.newFixedThreadPool(MULTI_CONNECTION_COUNT);
        ArrayDeque<Future<byte[]>> pendingReads = new ArrayDeque<>();
        long chunkCount = (fileSize - 1) / MULTI_CONNECTION_CHUNK_SIZE + 1;
        long nextChunk = 0;
        try {
            while (nextChunk < chunkCount && pendingReads.size() < MULTI_CONNECTION_COUNT) {
                if (listener != null) listener.checkpoint();
                long chunk = nextChunk++;
                long offset = chunk * MULTI_CONNECTION_CHUNK_SIZE;
                int length = (int) Math.min(MULTI_CONNECTION_CHUNK_SIZE, fileSize - offset);
                com.hierynomus.smbj.share.File file = files.get((int) (chunk % MULTI_CONNECTION_COUNT));
                pendingReads.add(executor.submit(() -> readChunk(file, offset, length, listener)));
            }
            while (!pendingReads.isEmpty()) {
                byte[] data = awaitRead(pendingReads.getFirst(), listener);
                pendingReads.removeFirst();
                destination.write(data);
                if (nextChunk < chunkCount) {
                    if (listener != null) listener.checkpoint();
                    long chunk = nextChunk++;
                    long offset = chunk * MULTI_CONNECTION_CHUNK_SIZE;
                    int length = (int) Math.min(MULTI_CONNECTION_CHUNK_SIZE, fileSize - offset);
                    com.hierynomus.smbj.share.File file = files.get((int) (chunk % MULTI_CONNECTION_COUNT));
                    pendingReads.addLast(executor.submit(() -> readChunk(file, offset, length, listener)));
                }
            }
        } finally {
            for (Future<byte[]> pendingRead : pendingReads) pendingRead.cancel(true);
            executor.shutdownNow();
        }
    }

    private static byte[] readChunk(com.hierynomus.smbj.share.File file, long fileOffset, int length,
                                    TransferListener listener) throws IOException {
        byte[] buffer = new byte[length];
        int bytesRead = 0;
        while (bytesRead < length) {
            if (listener != null) listener.checkpoint();
            int count = file.read(buffer, fileOffset + bytesRead, bytesRead, length - bytesRead);
            if (count <= 0) throw new IOException(count < 0
                    ? "SMB file changed during transfer" : "SMB read made no progress");
            bytesRead += count;
        }
        return buffer;
    }

    @Override public synchronized void upload(String path, InputStream source, long size) throws IOException {
        try {
            ensureConnected();
            SharePath target = resolveWritable(path);
            try (com.hierynomus.smbj.share.File file = target.share.openFile(smbPath(target.path),
                    EnumSet.of(AccessMask.GENERIC_WRITE), EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                    SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_CREATE,
                    EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE))) {
                if (size >= 0 && size < MULTI_CONNECTION_THRESHOLD) {
                    uploadSingleConnection(file, source);
                    return;
                }
                List<TransferChannel> channels = new ArrayList<>(MULTI_CONNECTION_COUNT - 1);
                try {
                    for (int lane = 1; lane < MULTI_CONNECTION_COUNT; lane++)
                        channels.add(openTransferChannel(target.shareName, target.path, true));
                    List<com.hierynomus.smbj.share.File> files = new ArrayList<>(MULTI_CONNECTION_COUNT);
                    files.add(file);
                    for (TransferChannel channel : channels) files.add(channel.file);
                    uploadMultiConnection(files, source);
                } finally {
                    for (TransferChannel channel : channels) channel.close();
                }
            }
        } catch (Exception e) { throw error(e); }
    }

    private static void uploadSingleConnection(com.hierynomus.smbj.share.File file,
                                               InputStream source) throws IOException {
        ArrayDeque<PendingWrite> pendingWrites = new ArrayDeque<>();
        long offset = 0;
        try {
            while (true) {
                byte[] buffer = new byte[SINGLE_CONNECTION_CHUNK_SIZE];
                int length = 0;
                while (length < buffer.length) {
                    int count = source.read(buffer, length, buffer.length - length);
                    if (count < 0) break;
                    length += count;
                }
                if (length == 0) break;
                byte[] data = length == buffer.length ? buffer : Arrays.copyOf(buffer, length);
                Future<Long> future = file.writeAsync(data, offset, 0, data.length);
                pendingWrites.add(new PendingWrite(data, future));
                offset += data.length;
                if (pendingWrites.size() == SINGLE_CONNECTION_MAX_IN_FLIGHT)
                    awaitWrite(pendingWrites.removeFirst());
            }
            while (!pendingWrites.isEmpty()) awaitWrite(pendingWrites.removeFirst());
        } finally {
            for (PendingWrite pendingWrite : pendingWrites) pendingWrite.future.cancel(true);
        }
    }

    private static void uploadMultiConnection(List<com.hierynomus.smbj.share.File> files,
                                              InputStream source) throws IOException {
        ArrayDeque<PendingWrite> pendingWrites = new ArrayDeque<>();
        long offset = 0;
        long chunk = 0;
        try {
            while (true) {
                if (pendingWrites.size() == MULTI_CONNECTION_COUNT)
                    awaitWrite(pendingWrites.removeFirst());
                byte[] buffer = new byte[MULTI_CONNECTION_CHUNK_SIZE];
                int length = 0;
                while (length < buffer.length) {
                    int count = source.read(buffer, length, buffer.length - length);
                    if (count < 0) break;
                    length += count;
                }
                if (length == 0) break;
                byte[] data = length == buffer.length ? buffer : Arrays.copyOf(buffer, length);
                com.hierynomus.smbj.share.File file = files.get((int) (chunk % MULTI_CONNECTION_COUNT));
                Future<Long> future = file.writeAsync(data, offset, 0, data.length);
                pendingWrites.addLast(new PendingWrite(data, future));
                offset += data.length;
                chunk++;
            }
            while (!pendingWrites.isEmpty()) awaitWrite(pendingWrites.removeFirst());
        } finally {
            for (PendingWrite pendingWrite : pendingWrites) pendingWrite.future.cancel(true);
        }
    }

    private TransferChannel openTransferChannel(String shareName, String path, boolean writable) throws IOException {
        SmbConfig config = smbConfig();
        SMBClient transferClient = new SMBClient(config);
        try {
            Connection transferConnection = transferClient.connect(host, port);
            Session transferSession = transferConnection.authenticate(username.isEmpty()
                    ? AuthenticationContext.anonymous()
                    : new AuthenticationContext(username, password.toCharArray(), domain));
            com.hierynomus.smbj.share.Share connectedShare = transferSession.connectShare(shareName);
            if (!(connectedShare instanceof DiskShare)) {
                connectedShare.close();
                throw new IOException("SMB location is not a disk share");
            }
            DiskShare transferShare = (DiskShare) connectedShare;
            com.hierynomus.smbj.share.File transferFile = transferShare.openFile(smbPath(path),
                    EnumSet.of(writable ? AccessMask.GENERIC_WRITE : AccessMask.GENERIC_READ), null,
                    SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN,
                    EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE));
            return new TransferChannel(transferClient, transferSession, transferShare, transferFile);
        } catch (Exception e) {
            transferClient.close();
            throw error(e);
        }
    }

    @Override public synchronized void mkdir(String path) throws IOException {
        try {
            ensureConnected();
            SharePath target = resolveWritable(path);
            target.share.mkdir(smbPath(target.path));
        } catch (Exception e) { throw error(e); }
    }

    @Override public synchronized void rename(String path, String destination) throws IOException {
        try {
            ensureConnected();
            SharePath source = resolveWritable(path);
            SharePath target = resolveWritable(destination);
            if (source.share != target.share) throw new IOException("Cannot rename between SMB shares");
            try (DiskEntry entry = source.share.open(smbPath(source.path), EnumSet.of(AccessMask.DELETE), null,
                    SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)) {
                entry.rename(smbPath(target.path), false);
            }
        } catch (Exception e) { throw error(e); }
    }

    @Override public synchronized void delete(String path, boolean directory) throws IOException {
        delete(path, directory, null);
    }

    synchronized void delete(String path, boolean directory,
                             TransferListener listener) throws IOException {
        try {
            if (listener != null) listener.checkpoint();
            ensureConnected();
            SharePath target = resolveWritable(path);
            if (directory) {
                for (RemoteEntry child : list(path)) delete(child.path, child.directory, listener);
                if (listener != null) listener.checkpoint();
                target.share.rmdir(smbPath(target.path), false);
                return;
            }
            target.share.rm(smbPath(target.path));
        } catch (Exception e) { throw error(e); }
    }

    @Override public RemoteSpace space() { return null; }

    @Override public synchronized void close() {
        closed = true;
        closeTransport();
    }

    private static final class SharePath {
        final String shareName;
        final DiskShare share;
        final String path;

        SharePath(String shareName, DiskShare share, String path) {
            this.shareName = shareName;
            this.share = share;
            this.path = path;
        }
    }

    private static final class TransferChannel {
        final SMBClient client;
        final Session session;
        final DiskShare share;
        final com.hierynomus.smbj.share.File file;

        TransferChannel(SMBClient client, Session session, DiskShare share,
                        com.hierynomus.smbj.share.File file) {
            this.client = client;
            this.session = session;
            this.share = share;
            this.file = file;
        }

        void close() {
            try { file.close(); } catch (Exception ignored) {}
            try { share.close(); } catch (Exception ignored) {}
            try { session.close(); } catch (Exception ignored) {}
            client.close();
        }
    }

    private static final class PendingWrite {
        final byte[] buffer;
        final Future<Long> future;

        PendingWrite(byte[] buffer, Future<Long> future) {
            this.buffer = buffer;
            this.future = future;
        }
    }
}
