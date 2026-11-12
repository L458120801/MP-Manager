package io.github.abdurazaaqmohammed.features.files.network;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.hierynomus.mssmb2.SMB2Dialect;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.abdurazaaqmohammed.utils.FileUtils;
import io.github.abdurazaaqmohammed.utils.TransferCancelledException;
import io.github.abdurazaaqmohammed.utils.TransferControl;

import static org.junit.Assert.*;

public class RemoteTransfersTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private static class MemoryClient implements RemoteClient {
        final Map<String, byte[]> files = new LinkedHashMap<>();
        boolean failDownload;
        int deletes;
        int lists;
        MemoryClient() { files.put("", null); }
        @Override public List<RemoteEntry> list(String directory) {
            lists++;
            List<RemoteEntry> result = new ArrayList<>();
            for (String path : files.keySet()) if (!path.equals(directory) && RemotePath.parent(path).equals(directory)) result.add(stat(path));
            return result;
        }
        @Override public RemoteEntry stat(String path) {
            if (!files.containsKey(path)) return null;
            byte[] bytes = files.get(path);
            return new RemoteEntry(path, bytes == null, bytes == null ? 0 : bytes.length, 0);
        }
        @Override public void download(String path, OutputStream destination) throws IOException {
            if (failDownload) { destination.write(1); throw new IOException("Simulated connection loss"); }
            destination.write(files.get(path));
        }
        @Override public void upload(String path, InputStream source, long size) throws IOException {
            if (files.containsKey(path)) throw new IOException("Conflict");
            files.put(path, source.readAllBytes());
        }
        @Override public void mkdir(String path) { files.put(path, null); }
        @Override public void rename(String path, String destination) { files.put(destination, files.remove(path)); }
        @Override public void delete(String path, boolean directory) {
            deletes++;
            files.keySet().removeIf(p -> p.equals(path) || p.startsWith(path + "/"));
        }
        @Override public void close() {}
    }

    @Test public void failedMoveKeepsSourceAndRemovesPartialLocalFile() throws Exception {
        MemoryClient client = new MemoryClient();
        client.files.put("source", new byte[]{1, 2, 3});
        client.failDownload = true;
        File output = new File(temporary.getRoot(), "destination");
        assertThrows(IOException.class, () -> RemoteTransfers.moveToLocal(client, client.stat("source"), output));
        assertNotNull(client.stat("source"));
        assertEquals(0, client.deletes);
        assertFalse(output.exists());
        assertEquals(0, temporary.getRoot().list().length);
    }

    @Test public void successfulMoveDeletesOnlyAfterCommittedDownload() throws Exception {
        MemoryClient client = new MemoryClient();
        client.files.put("source", new byte[]{1, 2, 3});
        File output = new File(temporary.getRoot(), "destination");
        RemoteTransfers.moveToLocal(client, client.stat("source"), output);
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(output.toPath()));
        assertNull(client.stat("source"));
        assertEquals(1, client.deletes);
    }

    @Test public void cancellingMoveSourceCleanupPreservesLaterSources() {
        TransferControl control = new TransferControl();
        MemoryClient client = new MemoryClient() {
            @Override public void delete(String path, boolean directory) {
                super.delete(path, directory);
                if (deletes == 1) control.cancel();
            }
        };
        client.files.put("first", new byte[]{1});
        client.files.put("second", new byte[]{2});
        RemoteFile.Connection connection = new RemoteFile.Connection(client, "smb://test/");
        List<RemoteFile> sources = List.of(new RemoteFile(connection, client.stat("first")),
                new RemoteFile(connection, client.stat("second")));
        FileUtils.TransferListener listener = new FileUtils.TransferListener() {
            @Override public void onFile(File source, File destination) {}
            @Override public void onBytes(long count) {}
            @Override public void checkpoint() { control.checkpoint(); }
        };

        assertThrows(TransferCancelledException.class, () -> RemoteTransfers.deleteMovedSources(sources, listener));
        assertNull(client.stat("first"));
        assertNotNull(client.stat("second"));
        assertEquals(1, client.deletes);
    }

    @Test public void failedRecursiveMoveKeepsEntireRemoteSubtree() throws Exception {
        MemoryClient client = new MemoryClient();
        client.files.put("source", null);
        client.files.put("source/child", new byte[]{1, 2, 3});
        client.failDownload = true;
        assertThrows(IOException.class, () -> RemoteTransfers.moveToLocal(client, client.stat("source"),
                new File(temporary.getRoot(), "destination")));
        assertNotNull(client.stat("source"));
        assertNotNull(client.stat("source/child"));
        assertEquals(0, client.deletes);
    }

    @Test public void conflictsPreserveBothFiles() throws Exception {
        MemoryClient client = new MemoryClient();
        client.files.put("source", new byte[]{1});
        File output = temporary.newFile("existing");
        Files.write(output.toPath(), new byte[]{9});
        assertThrows(IOException.class, () -> RemoteTransfers.moveToLocal(client, client.stat("source"), output));
        assertArrayEquals(new byte[]{9}, Files.readAllBytes(output.toPath()));
        assertNotNull(client.stat("source"));
    }

    @Test public void rejectTruncatedDownloadBeforeDeletingSource() throws Exception {
        MemoryClient client = new MemoryClient();
        client.files.put("source", new byte[]{1});
        assertThrows(IOException.class, () -> RemoteTransfers.moveToLocal(client,
                new RemoteEntry("source", false, 5, 0), new File(temporary.getRoot(), "output")));
        assertEquals(0, client.deletes);
    }

    @Test public void recursiveRoundTripIncludesEmptyFilesAndFolders() throws Exception {
        MemoryClient client = new MemoryClient();
        File source = temporary.newFolder("source");
        assertTrue(new File(source, "empty-folder").mkdir());
        Files.write(new File(source, "empty-file").toPath(), new byte[0]);
        Files.write(new File(source, "data").toPath(), new byte[]{1, 2});
        RemoteTransfers.upload(client, "", source);
        File destination = new File(temporary.getRoot(), "output");
        RemoteTransfers.download(client, client.stat("source"), destination);
        assertTrue(new File(destination, "empty-folder").isDirectory());
        assertTrue(new File(destination, "empty-file").isFile());
        assertArrayEquals(new byte[]{1, 2}, Files.readAllBytes(new File(destination, "data").toPath()));
        assertThrows(IOException.class, () -> RemoteTransfers.upload(client, "", source));
    }

    @Test public void preparedDownloadReusesItsTreeAndReportsFileAndByteProgress() throws Exception {
        MemoryClient client = new MemoryClient();
        client.files.put("source", null);
        client.files.put("source/empty-folder", null);
        client.files.put("source/sub", null);
        client.files.put("source/sub/empty", new byte[0]);
        client.files.put("source/sub/data", new byte[]{1, 2, 3});
        RemoteTransfers.DownloadPlan plan = RemoteTransfers.prepareDownload(client, client.stat("source"), null);
        int listedFolders = client.lists;
        AtomicInteger bytes = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        RemoteTransfers.download(client, plan, new File(temporary.getRoot(), "planned"), new io.github.abdurazaaqmohammed.utils.FileUtils.TransferListener() {
            @Override public void onFile(File source, File destination) {}
            @Override public void onBytes(long count) { bytes.addAndGet((int) count); }
            @Override public void onFileComplete() { completed.incrementAndGet(); }
        }, true);
        assertEquals(2, plan.measure.files);
        assertEquals(3, plan.measure.bytes);
        assertEquals(listedFolders, client.lists);
        assertEquals(3, client.lists);
        assertEquals(3, bytes.get());
        assertEquals(2, completed.get());
        assertTrue(new File(temporary.getRoot(), "planned/empty-folder").isDirectory());
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(new File(temporary.getRoot(), "planned/sub/data").toPath()));
    }

    @Test public void uploadReportsBytesAndCompletedFiles() throws Exception {
        MemoryClient client = new MemoryClient();
        File source = temporary.newFolder("upload-source");
        Files.write(new File(source, "empty").toPath(), new byte[0]);
        Files.write(new File(source, "data").toPath(), new byte[]{1, 2, 3});
        AtomicInteger bytes = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        RemoteTransfers.upload(client, "", source, new io.github.abdurazaaqmohammed.utils.FileUtils.TransferListener() {
            @Override public void onFile(File file, File destination) {}
            @Override public void onBytes(long count) { bytes.addAndGet((int) count); }
            @Override public void onFileComplete() { completed.incrementAndGet(); }
        });
        assertEquals(3, bytes.get());
        assertEquals(2, completed.get());
        assertArrayEquals(new byte[]{1, 2, 3}, client.files.get("upload-source/data"));
    }

    @Test public void rejectUnsafeServerChildren() {
        assertThrows(IOException.class, () -> RemoteTransfers.requireChild("folder", new RemoteEntry("other/file", false, 1, 0)));
        assertThrows(IOException.class, () -> RemoteTransfers.requireChild("folder", new RemoteEntry("folder", true, 0, 0)));
    }

    @Test public void validateProtocolPathsAndPreserveLiteralPercentNames() {
        assertEquals("folder/測試 %2F +.txt", RemotePath.child("/folder/", "測試 %2F +.txt"));
        assertEquals("folder", RemotePath.parent("folder/file"));
        assertEquals("", RemotePath.parent(""));
        for (String bad : new String[]{"..", ".", "", "a/b", "a\\b", "a\u0000b", "a\nb"})
            assertThrows(IllegalArgumentException.class, () -> RemotePath.validateName(bad));
        assertThrows(IllegalArgumentException.class, () -> RemotePath.normalize("folder/../escape"));
        assertEquals("folder\\file", SmbClient.smbPath("folder/file"));
        assertThrows(IllegalArgumentException.class, () -> SmbClient.smbPath("file:stream"));
    }

    @Test public void validateSmbProfiles() {
        NetworkProfile p = new NetworkProfile();
        p.protocol = "smb";
        p.address = "smb://127.0.0.1:1445/Documents";
        p.validate();
        assertEquals("smb://127.0.0.1:1445/", p.canonicalAddress());
        p.address = "smb://host/";
        p.validate();
        assertEquals("smb://host/", p.canonicalAddress());
        for (String bad : new String[]{"smb://", "smb://user:pass@host/", "http://host/",
                "smb://host:0/", "smb://host:65536/", "smb://host/?password=secret"}) {
            p.address = bad;
            assertThrows(IllegalArgumentException.class, p::validate);
        }
    }

    @Test public void smbClientUsesUnsignedSmb2DialectsOnly() {
        com.hierynomus.smbj.SmbConfig config = SmbClient.smbConfig();
        assertEquals(EnumSet.of(SMB2Dialect.SMB_2_1, SMB2Dialect.SMB_2_0_2), config.getSupportedDialects());
        assertFalse(config.isSigningEnabled());
    }

    @Test public void classifyAndroid17LocalNetworksWithoutBlockingPublicServers() throws Exception {
        for (String local : new String[]{"127.0.0.1", "192.168.1.1", "10.1.2.3", "172.16.0.1", "169.254.1.1", "::1", "fd00::1"})
            assertTrue(local, NetworkAccess.isLocal(java.net.InetAddress.getByName(local)));
        assertFalse(NetworkAccess.isLocal(java.net.InetAddress.getByName("8.8.8.8")));
        assertTrue(NetworkAccess.requiresPermission("smb://nas/share/"));
        assertTrue(NetworkAccess.requiresPermission("https://nas.local/dav/"));
    }

    @Test public void onlyMissingSmbStatusesAreTreatedAsAbsent() {
        assertTrue(SmbClient.isMissing(com.hierynomus.mserref.NtStatus.STATUS_NO_SUCH_FILE.getValue()));
        assertTrue(SmbClient.isMissing(com.hierynomus.mserref.NtStatus.STATUS_OBJECT_NAME_NOT_FOUND.getValue()));
        assertTrue(SmbClient.isMissing(com.hierynomus.mserref.NtStatus.STATUS_OBJECT_PATH_NOT_FOUND.getValue()));
        assertFalse(SmbClient.isMissing(com.hierynomus.mserref.NtStatus.STATUS_ACCESS_DENIED.getValue()));
        assertFalse(SmbClient.isMissing(com.hierynomus.mserref.NtStatus.STATUS_LOGON_FAILURE.getValue()));
    }
}
