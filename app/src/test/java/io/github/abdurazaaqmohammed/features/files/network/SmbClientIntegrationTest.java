package io.github.abdurazaaqmohammed.features.files.network;

import org.junit.Assume;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import io.github.abdurazaaqmohammed.utils.FileUtils;
import io.github.abdurazaaqmohammed.utils.TransferCancelledException;
import io.github.abdurazaaqmohammed.utils.TransferControl;

import static org.junit.Assert.*;

/** Opt-in integration test. Uses a unique folder; never changes existing share contents. */
public class SmbClientIntegrationTest {
    @Test public void authenticatedSmb2RoundTripAndOperations() throws Exception {
        String host = System.getenv("MP_TEST_SMB_HOST");
        Assume.assumeTrue("Set MP_TEST_SMB_HOST to enable a live SMB test", host != null && !host.isEmpty());
        int port = Integer.parseInt(env("MP_TEST_SMB_PORT", "445"));
        try (SmbClient client = new SmbClient(host, port, env("MP_TEST_SMB_USER", ""),
                env("MP_TEST_SMB_PASSWORD", ""), env("MP_TEST_SMB_DOMAIN", ""))) {
            assertNull(client.space());
            assertTrue(client.stat("").directory);
            List<RemoteEntry> shares = client.list("");
            assertTrue(shares.stream().anyMatch(entry -> entry.name().equalsIgnoreCase("hidden$")));
            assertFalse(shares.stream().anyMatch(entry -> entry.name().equalsIgnoreCase("IPC$")));
            assertFalse(shares.stream().anyMatch(entry -> entry.name().equalsIgnoreCase("printer")));
            assertTrue(shares.size() >= 22);
            String share = shares.stream().filter(entry -> entry.name().equalsIgnoreCase(env("MP_TEST_SMB_SHARE", "test")))
                    .findFirst().orElseThrow(AssertionError::new).path;
            assertThrows(IOException.class, () -> client.mkdir("server-root-write"));
            String folder = RemotePath.child(share, "mp-network-test-" + UUID.randomUUID());
            client.mkdir(folder);
            Throwable failure = null;
            try {
                assertTrue(client.stat(folder).directory);
                String file = folder + "/space + 測試.txt";
                byte[] bytes = new byte[]{0, 1, 2, 3, 4};
                client.upload(file, new ByteArrayInputStream(bytes), bytes.length);
                assertEquals(1, client.list(folder).size());
                assertEquals(bytes.length, client.stat(file).size);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                client.download(file, output);
                assertArrayEquals(bytes, output.toByteArray());
                File largeSource = File.createTempFile("mp-smb-source-", ".bin");
                File largeDownload = File.createTempFile("mp-smb-download-", ".bin");
                String largeFile = folder + "/parallel-transfer.bin";
                try {
                    try (FileOutputStream generated = new FileOutputStream(largeSource)) {
                        Random random = new Random(42);
                        byte[] buffer = new byte[1024 * 1024];
                        for (long remaining = 32L * 1024 * 1024 + 137; remaining > 0;) {
                            int count = (int) Math.min(buffer.length, remaining);
                            random.nextBytes(buffer);
                            generated.write(buffer, 0, count);
                            remaining -= count;
                        }
                    }
                    try (FileInputStream input = new FileInputStream(largeSource)) {
                        client.upload(largeFile, input, largeSource.length());
                    }
                    try (FileOutputStream download = new FileOutputStream(largeDownload)) {
                        client.download(largeFile, download);
                    }
                    assertEquals(largeSource.length(), client.stat(largeFile).size);
                    assertArrayEquals(sha256(largeSource), sha256(largeDownload));

                    File pausedDownload = new File(largeDownload.getParentFile(), "mp-smb-paused-" + UUID.randomUUID());
                    TransferControl pauseControl = new TransferControl();
                    AtomicLong pausedBytes = new AtomicLong();
                    AtomicReference<Throwable> pauseFailure = new AtomicReference<>();
                    FileUtils.TransferListener pauseListener = listener(pauseControl, pausedBytes, pauseControl::pause);
                    RemoteTransfers.DownloadPlan pausePlan = RemoteTransfers.prepareDownload(client,
                            client.stat(largeFile), pauseListener);
                    Thread pauseTransfer = new Thread(() -> {
                        try { RemoteTransfers.download(client, pausePlan, pausedDownload, pauseListener, true); }
                        catch (Throwable e) { pauseFailure.set(e); }
                    });
                    pauseTransfer.start();
                    try {
                        waitForProgress(pausedBytes, pauseTransfer);
                        waitForPause(pauseControl, pauseTransfer);
                        long bytesAtPause = pausedBytes.get();
                        Thread.sleep(200);
                        assertEquals(bytesAtPause, pausedBytes.get());
                        pauseControl.resume();
                        pauseTransfer.join(30_000);
                        assertFalse("Paused SMB download did not finish after resume", pauseTransfer.isAlive());
                        assertNull(pauseFailure.get());
                        assertArrayEquals(sha256(largeSource), sha256(pausedDownload));
                    } finally {
                        pauseControl.cancel();
                        pauseTransfer.join(30_000);
                        pausedDownload.delete();
                    }

                    File cancelledDownload = new File(largeDownload.getParentFile(), "mp-smb-cancelled-" + UUID.randomUUID());
                    TransferControl cancelControl = new TransferControl();
                    AtomicLong cancelledBytes = new AtomicLong();
                    AtomicReference<Throwable> cancelFailure = new AtomicReference<>();
                    FileUtils.TransferListener cancelListener = listener(cancelControl, cancelledBytes, cancelControl::cancel);
                    RemoteTransfers.DownloadPlan cancelPlan = RemoteTransfers.prepareDownload(client,
                            client.stat(largeFile), cancelListener);
                    Thread cancelTransfer = new Thread(() -> {
                        try { RemoteTransfers.download(client, cancelPlan, cancelledDownload, cancelListener, true); }
                        catch (Throwable e) { cancelFailure.set(e); }
                    });
                    cancelTransfer.start();
                    try {
                        waitForProgress(cancelledBytes, cancelTransfer);
                        cancelTransfer.join(30_000);
                        assertFalse("Cancelled SMB download did not stop", cancelTransfer.isAlive());
                        assertTrue(TransferCancelledException.isCancellation(cancelFailure.get()));
                        assertFalse(cancelledDownload.exists());
                    } finally {
                        cancelControl.cancel();
                        cancelTransfer.join(30_000);
                        cancelledDownload.delete();
                    }

                    String cancellableFolder = folder + "/cancel-delete";
                    client.mkdir(cancellableFolder);
                    client.upload(cancellableFolder + "/first", new ByteArrayInputStream(bytes), bytes.length);
                    client.upload(cancellableFolder + "/second", new ByteArrayInputStream(bytes), bytes.length);
                    TransferControl deleteControl = new TransferControl();
                    AtomicInteger deleteCheckpoints = new AtomicInteger();
                    FileUtils.TransferListener deleteListener = new FileUtils.TransferListener() {
                        @Override public void onFile(File source, File destination) {}
                        @Override public void onBytes(long count) {}
                        @Override public void checkpoint() {
                            if (deleteCheckpoints.incrementAndGet() == 3) deleteControl.cancel();
                            deleteControl.checkpoint();
                        }
                    };
                    IOException deleteFailure = assertThrows(IOException.class,
                            () -> client.delete(cancellableFolder, true, deleteListener));
                    assertTrue(TransferCancelledException.isCancellation(deleteFailure));
                    assertEquals(1, client.list(cancellableFolder).size());
                } finally {
                    largeSource.delete();
                    largeDownload.delete();
                }
                assertThrows(IOException.class, () -> client.upload(file, new ByteArrayInputStream(bytes), bytes.length));
                client.rename(file, folder + "/renamed.txt");
                assertNull(client.stat(file));
                client.upload(folder + "/empty", new ByteArrayInputStream(new byte[0]), 0);
                assertEquals(0, client.stat(folder + "/empty").size);
                output.reset();
                client.download(folder + "/empty", output);
                assertEquals(0, output.size());
                assertThrows(IOException.class, () -> client.rename(folder + "/empty", folder + "/renamed.txt"));
                client.mkdir(folder + "/nested");
                client.upload(folder + "/nested/file", new ByteArrayInputStream(bytes), bytes.length);
            } catch (Exception | AssertionError e) {
                failure = e;
                throw e;
            } finally {
                try { client.delete(folder, true); }
                catch (Exception cleanup) {
                    if (failure != null) failure.addSuppressed(cleanup);
                    else throw cleanup;
                }
            }
            assertNull(client.stat(folder));
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value;
    }

    private static byte[] sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[1024 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return digest.digest();
    }

    private static FileUtils.TransferListener listener(TransferControl control, AtomicLong bytes,
                                                       Runnable onFirstBytes) {
        AtomicInteger first = new AtomicInteger();
        return new FileUtils.TransferListener() {
            @Override public void onFile(File source, File destination) {}
            @Override public void onBytes(long count) {
                bytes.addAndGet(count);
                if (first.getAndIncrement() == 0) onFirstBytes.run();
            }
            @Override public void checkpoint() { control.checkpoint(); }
            @Override public TransferControl control() { return control; }
        };
    }

    private static void waitForProgress(AtomicLong bytes, Thread transfer) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (bytes.get() == 0 && transfer.isAlive() && System.currentTimeMillis() < deadline) Thread.sleep(10);
        assertTrue("SMB download did not report progress", bytes.get() > 0);
    }

    private static void waitForPause(TransferControl control, Thread transfer) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (control.parkedWorkers() == 0 && transfer.isAlive() && System.currentTimeMillis() < deadline) Thread.sleep(10);
        assertTrue("SMB download did not reach a pause checkpoint", control.parkedWorkers() > 0);
    }
}
