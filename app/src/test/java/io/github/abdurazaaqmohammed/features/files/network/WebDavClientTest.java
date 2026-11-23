package io.github.abdurazaaqmohammed.features.files.network;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.github.abdurazaaqmohammed.utils.FileUtils;

import static org.junit.Assert.*;

public class WebDavClientTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private MockWebServer server;
    private WebDavClient client;
    private String address;
    private okhttp3.internal.platform.Platform platform;
    // null = directory, non-null = file bytes
    private final Map<String, byte[]> resources = new LinkedHashMap<>();

    @Before public void start() throws Exception {
        // The suite has an Android Build stub for APK tests. Real HTTP tests
        // must explicitly use the JVM platform, not Android's unmocked TLS APIs.
        platform = okhttp3.internal.platform.Platform.Companion.get();
        okhttp3.internal.platform.Platform.Companion.resetForTests(new okhttp3.internal.platform.Platform());
        resources.put("", null);
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) { return serve(request); }
        });
        server.start();
        address = server.url("/dav/").toString();
        client = new WebDavClient(address, "tester", "secret");
    }

    @After public void stop() throws IOException {
        if (client != null) client.close();
        if (server != null) server.shutdown();
        okhttp3.internal.platform.Platform.Companion.resetForTests(platform);
    }

    private MockResponse serve(RecordedRequest request) {
            if (!"Basic dGVzdGVyOnNlY3JldA==".equals(request.getHeader("Authorization"))) return reply(401, "");
            String path = URI.create(request.getRequestUrl().toString()).getPath().substring(5);
            if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            String method = request.getMethod();
            synchronized (resources) {
                switch (method) {
                    case "PROPFIND": {
                        if (!resources.containsKey(path)) return reply(404, "");
                        StringBuilder xml = new StringBuilder("<D:multistatus xmlns:D=\"DAV:\">");
                        xml.append(properties(path, resources.get(path)));
                        if ("1".equals(request.getHeader("Depth"))) {
                            for (Map.Entry<String, byte[]> entry : resources.entrySet()) {
                                if (!entry.getKey().equals(path) && RemotePath.parent(entry.getKey()).equals(path))
                                    xml.append(properties(entry.getKey(), entry.getValue()));
                            }
                        }
                        return reply(207, xml.append("</D:multistatus>").toString());
                    }
                    case "GET": {
                        if (!resources.containsKey(path) || resources.get(path) == null) return reply(404, "");
                        byte[] bytes = resources.get(path);
                        return new MockResponse().setResponseCode(200).setBody(new Buffer().write(bytes));
                    }
                    case "PUT": {
                        assertEquals("*", request.getHeader("If-None-Match"));
                        if (resources.containsKey(path)) return reply(412, "");
                        resources.put(path, request.getBody().readByteArray());
                        return reply(201, "");
                    }
                    case "COPY": {
                        assertEquals("F", request.getHeader("Overwrite"));
                        String destination = URI.create(request.getHeader("Destination")).getPath().substring(5);
                        if (destination.endsWith("/")) destination = destination.substring(0, destination.length() - 1);
                        if (!resources.containsKey(path)) return reply(404, "");
                        if (resources.containsKey(destination)) return reply(412, "");
                        String parent = RemotePath.parent(destination);
                        if (!resources.containsKey(parent) || resources.get(parent) != null) return reply(409, "");
                        byte[] data = resources.get(path);
                        if (data != null) {
                            resources.put(destination, data.clone());
                            return reply(201, "");
                        }
                        if (!"infinity".equals(request.getHeader("Depth"))) return reply(400, "");
                        Map<String, byte[]> copied = new LinkedHashMap<>();
                        copied.put(destination, null);
                        for (Map.Entry<String, byte[]> entry : resources.entrySet()) {
                            if (entry.getKey().startsWith(path + "/"))
                                copied.put(destination + entry.getKey().substring(path.length()),
                                        entry.getValue() == null ? null : entry.getValue().clone());
                        }
                        resources.putAll(copied);
                        return reply(201, "");
                    }
                    case "MKCOL": {
                        if (resources.containsKey(path)) return reply(405, "");
                        resources.put(path, null); return reply(201, "");
                    }
                    case "MOVE": {
                        assertEquals("F", request.getHeader("Overwrite"));
                        String destination = URI.create(request.getHeader("Destination")).getPath().substring(5);
                        if (resources.containsKey(destination)) return reply(412, "");
                        resources.put(destination, resources.remove(path)); return reply(201, "");
                    }
                    case "DELETE": {
                        final String deleted = path;
                        resources.keySet().removeIf(p -> p.equals(deleted) || p.startsWith(deleted + "/"));
                        return reply(204, "");
                    }
                    default: return reply(405, "");
                }
            }
    }

    private String properties(String path, byte[] data) {
        String encoded;
        try { encoded = new URI(null, null, "/dav/" + path, null).toASCIIString(); }
        catch (Exception e) { throw new AssertionError(e); }
        if (data == null && !encoded.endsWith("/")) encoded += "/";
        return "<D:response><D:href>" + encoded.replace("&", "&amp;") + "</D:href><D:propstat><D:prop>"
                + "<D:resourcetype>" + (data == null ? "<D:collection/>" : "") + "</D:resourcetype>"
                + "<D:getcontentlength>" + (data == null ? 0 : data.length) + "</D:getcontentlength>"
                + "<D:getlastmodified>Wed, 21 Oct 2015 07:28:00 GMT</D:getlastmodified>"
                + "</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>";
    }

    private static MockResponse reply(int code, String content) {
        return new MockResponse().setResponseCode(code).setHeader("Content-Type", "application/xml; charset=utf-8").setBody(content);
    }

    @Test public void roundTripUnicodeSpacesAndReservedCharacters() throws Exception {
        String name = "測試 + 100% # &.txt";
        byte[] bytes = "WebDAV works".getBytes(StandardCharsets.UTF_8);
        client.upload(name, new ByteArrayInputStream(bytes), bytes.length);
        RemoteEntry entry = client.list("").get(0);
        assertEquals(name, entry.name());
        assertEquals(bytes.length, entry.size);
        assertTrue(entry.modified > 0);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        client.download(name, out);
        assertArrayEquals(bytes, out.toByteArray());
    }

    @Test public void recursiveWebdavUploadAvoidsStatRoundTrips() throws Exception {
        File folder = temporary.newFolder("tree");
        File nested = new File(folder, "nested");
        assertTrue(nested.mkdir());
        Files.write(new File(folder, "top.bin").toPath(), new byte[]{1});
        Files.write(new File(nested, "child.bin").toPath(), new byte[]{2});

        RemoteTransfers.upload(client, "", folder);

        assertEquals(4, server.getRequestCount());
        List<String> methods = new ArrayList<>();
        for (int i = 0; i < 4; i++) methods.add(server.takeRequest(5, TimeUnit.SECONDS).getMethod());
        Collections.sort(methods);
        assertEquals(Arrays.asList("MKCOL", "MKCOL", "PUT", "PUT"), methods);
        assertArrayEquals(new byte[]{1}, resources.get("tree/top.bin"));
        assertArrayEquals(new byte[]{2}, resources.get("tree/nested/child.bin"));
    }

    @Test public void stagedWebdavUploadUsesPutWithoutStat() throws Exception {
        File source = temporary.newFile("staged.bin");
        Files.write(source.toPath(), new byte[]{3, 4});
        RemoteTransfers.DownloadPlan plan = new RemoteTransfers.DownloadPlan(
                new RemoteEntry("source", false, 2, 0), List.of(), new FileUtils.Measure());

        RemoteTransfers.uploadPlanned(client, "", source, plan, null);

        assertEquals(1, server.getRequestCount());
        assertEquals("PUT", server.takeRequest(5, TimeUnit.SECONDS).getMethod());
        assertArrayEquals(new byte[]{3, 4}, resources.get("staged.bin"));
    }

    @Test public void serverSideCopyUsesOneCopyRequestAndNeverOverwrites() throws Exception {
        byte[] bytes = {1, 2, 3};
        resources.put("source", null);
        resources.put("source/sub", null);
        resources.put("source/sub/data", bytes);
        resources.put("target", null);
        try (WebDavClient same = new WebDavClient(address, "tester", "secret");
             WebDavClient wrongCredentials = new WebDavClient(address, "tester", "different");
             WebDavClient wrongCollection = new WebDavClient(server.url("/dav/other/").toString(), "tester", "secret")) {
            assertTrue(client.canServerCopyTo(same));
            assertFalse(client.canServerCopyTo(wrongCredentials));
            assertFalse(client.canServerCopyTo(wrongCollection));

            client.copyResource("source", "target/copied", true);
            RecordedRequest copy = server.takeRequest(5, TimeUnit.SECONDS);
            assertEquals("COPY", copy.getMethod());
            assertEquals("F", copy.getHeader("Overwrite"));
            assertEquals("infinity", copy.getHeader("Depth"));
            assertEquals("/dav/target/copied/", URI.create(copy.getHeader("Destination")).getPath());
            assertArrayEquals(bytes, resources.get("target/copied/sub/data"));

            client.copyResource("source/sub/data", "target/copied-file", false);
            RecordedRequest fileCopy = server.takeRequest(5, TimeUnit.SECONDS);
            assertEquals("COPY", fileCopy.getMethod());
            assertNull(fileCopy.getHeader("Depth"));
            assertEquals("/dav/target/copied-file", URI.create(fileCopy.getHeader("Destination")).getPath());
            assertArrayEquals(bytes, resources.get("target/copied-file"));

            assertThrows(IOException.class, () -> client.copyResource("source", "target/copied", true));
            assertEquals("COPY", server.takeRequest(5, TimeUnit.SECONDS).getMethod());
            assertArrayEquals(bytes, resources.get("target/copied/sub/data"));
        }
    }

    @Test public void compareLocalCopyBuffersWhenRequested() throws Exception {
        Assume.assumeTrue(Boolean.getBoolean("webdav.performance")
                || "true".equals(System.getenv("WEBDAV_PERFORMANCE")));
        byte[] data = new byte[32 * 1024 * 1024];
        new java.util.Random(1).nextBytes(data);
        File payload = temporary.newFile("payload.bin");
        Files.write(payload.toPath(), data);
        WebDavClient baseline = new WebDavClient(address, "tester", "secret", 1024 * 1024);
        WebDavClient candidate = new WebDavClient(address, "tester", "secret", 256 * 1024);
        try (baseline; candidate) {
            client.mkdir("benchmark");
            assertEquals("MKCOL", server.takeRequest(5, TimeUnit.SECONDS).getMethod());
            for (WebDavClient measured : new WebDavClient[]{baseline, candidate}) {
                String path = measured == baseline ? "benchmark/warm-baseline" : "benchmark/warm-candidate";
                try (FileInputStream input = new FileInputStream(payload)) { measured.upload(path, input, data.length); }
                assertEquals("PUT", server.takeRequest(5, TimeUnit.SECONDS).getMethod());
                ByteArrayOutputStream downloaded = new ByteArrayOutputStream(data.length);
                measured.download(path, downloaded);
                assertEquals("GET", server.takeRequest(5, TimeUnit.SECONDS).getMethod());
                assertArrayEquals(data, downloaded.toByteArray());
                resources.remove(path);
            }

            List<Long> currentUpload = new ArrayList<>();
            List<Long> currentDownload = new ArrayList<>();
            List<Long> candidateUpload = new ArrayList<>();
            List<Long> candidateDownload = new ArrayList<>();
            for (int run = 0; run < 5; run++) {
                WebDavClient[] order = run % 2 == 0
                        ? new WebDavClient[]{baseline, candidate} : new WebDavClient[]{candidate, baseline};
                for (WebDavClient measured : order) {
                    boolean current = measured == baseline;
                    String path = current ? "benchmark/baseline" : "benchmark/candidate";
                    resources.remove(path);
                    long start = System.nanoTime();
                    try (FileInputStream input = new FileInputStream(payload)) { measured.upload(path, input, data.length); }
                    long elapsed = System.nanoTime() - start;
                    assertEquals("PUT", server.takeRequest(5, TimeUnit.SECONDS).getMethod());
                    (current ? currentUpload : candidateUpload).add(elapsed);

                    ByteArrayOutputStream downloaded = new ByteArrayOutputStream(data.length);
                    start = System.nanoTime();
                    measured.download(path, downloaded);
                    elapsed = System.nanoTime() - start;
                    assertEquals("GET", server.takeRequest(5, TimeUnit.SECONDS).getMethod());
                    assertArrayEquals(data, downloaded.toByteArray());
                    (current ? currentDownload : candidateDownload).add(elapsed);
                }
            }
            System.out.printf(Locale.ROOT,
                    "WebDAV local fixture median MiB/s (1 MiB buffer upload/download; 256 KiB upload/download): %.1f / %.1f; %.1f / %.1f%n",
                    medianMiBPerSecond(currentUpload, data.length), medianMiBPerSecond(currentDownload, data.length),
                    medianMiBPerSecond(candidateUpload, data.length), medianMiBPerSecond(candidateDownload, data.length));
        }
    }

    private static double medianMiBPerSecond(List<Long> elapsedNanos, int bytes) {
        List<Long> sorted = new ArrayList<>(elapsedNanos);
        Collections.sort(sorted);
        return bytes * 1_000_000_000d / sorted.get(sorted.size() / 2) / (1024d * 1024d);
    }

    @Test public void emptyFilesCanBeUploadedAndDownloaded() throws Exception {
        client.upload("empty", new ByteArrayInputStream(new byte[0]), 0);
        assertFalse(client.stat("empty").directory);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        client.download("empty", out);
        assertEquals(0, out.size());
    }

    @Test public void createBrowseRenameAndDeleteFolder() throws Exception {
        client.mkdir("folder");
        client.upload("folder/a", new ByteArrayInputStream(new byte[]{1, 2}), -1);
        assertTrue(client.stat("folder").directory);
        assertEquals("folder/a", client.list("folder").get(0).path);
        client.rename("folder/a", "folder/b");
        assertNull(client.stat("folder/a"));
        assertEquals(2, client.stat("folder/b").size);
        client.delete("folder", true);
        assertTrue(client.list("").isEmpty());
    }

    @Test public void neverOverwriteExistingFiles() throws Exception {
        client.upload("a", new ByteArrayInputStream(new byte[]{1}), 1);
        client.upload("b", new ByteArrayInputStream(new byte[]{2}), 1);
        assertThrows(IOException.class, () -> client.upload("a", new ByteArrayInputStream(new byte[]{9}), 1));
        assertThrows(IOException.class, () -> client.rename("b", "a"));
        assertArrayEquals(new byte[]{1}, resources.get("a"));
        assertArrayEquals(new byte[]{2}, resources.get("b"));
    }

    @Test public void authenticationFailureIsNotAnEmptyListing() throws Exception {
        try (WebDavClient wrong = new WebDavClient(address, "tester", "wrong")) {
            assertThrows(IOException.class, () -> wrong.list(""));
            assertThrows(IOException.class, () -> wrong.stat("missing"));
        }
        assertNull(client.stat("missing"));
    }

    @Test public void refuseRootMutationAndCredentialUrls() {
        assertThrows(IOException.class, () -> client.delete("", true));
        assertThrows(IOException.class, () -> client.rename("", "x"));
        assertThrows(IOException.class, () -> client.mkdir(""));
        assertThrows(IOException.class, () -> client.upload("", new ByteArrayInputStream(new byte[0]), 0));
        assertThrows(IllegalArgumentException.class, () -> new WebDavClient("https://user:pass@example.com/dav/", "", ""));
    }

    @Test public void rejectExternalEntities() {
        String xml = "<!DOCTYPE x [<!ENTITY external SYSTEM 'file:///not-allowed'>]>"
                + "<D:multistatus xmlns:D='DAV:'>&external;</D:multistatus>";
        assertThrows(IOException.class, () -> client.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), client.url("")));
    }

    @Test public void rejectForeignHrefsAndEncodedPathSeparators() throws Exception {
        String xml = "<multistatus xmlns='DAV:'>" + properties("", null)
                + properties("valid", new byte[]{1})
                + properties("bad", new byte[]{1}).replace("/dav/bad", "/dav/bad%2Fescape")
                + properties("bad", new byte[]{1}).replace("/dav/bad", "/dav/bad%5Cescape")
                + properties("bad", new byte[]{1}).replace("/dav/bad", "/outside/bad")
                + properties("bad", new byte[]{1}).replace("/dav/bad", "http://other.invalid/dav/bad")
                + "</multistatus>";
        // properties() uses D:, while this root tests the alternate/default namespace.
        xml = xml.replace("<multistatus xmlns='DAV:'>", "<multistatus xmlns='DAV:' xmlns:D='DAV:'>");
        List<RemoteEntry> entries = client.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), client.url(""));
        assertEquals(2, entries.size());
        assertEquals("valid", entries.get(1).path);
    }

    @Test public void followCanonicalCollectionRedirectWithoutChangingMethod() throws Exception {
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                if (request.getMethod().equals("PROPFIND") && request.getPath().equals("/dav/redirect"))
                    return new MockResponse().setResponseCode(301).setHeader("Location", "/dav/redirect/");
                return serve(request);
            }
        });
        client.mkdir("redirect");
        assertTrue(client.stat("redirect").directory);
    }

    @Test public void blockCrossOriginAndOutsideCollectionRedirects() throws Exception {
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                String location = request.getPath().equals("/dav/foreign") ? "http://127.0.0.1:1/dav/secret" : "/outside/secret";
                return new MockResponse().setResponseCode(302).setHeader("Location", location);
            }
        });
        assertThrows(IOException.class, () -> client.stat("foreign"));
        assertThrows(IOException.class, () -> client.stat("outside"));
    }

    @Test public void partialCollectionDeleteIsNotReportedAsSuccess() {
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                return reply(207, "<D:multistatus xmlns:D='DAV:'><D:response><D:status>HTTP/1.1 403 Forbidden</D:status></D:response></D:multistatus>");
            }
        });
        assertThrows(IOException.class, () -> client.delete("folder", true));
        assertThrows(IOException.class, () -> client.rename("folder", "renamed"));
        assertThrows(IOException.class, () -> client.upload("file", new ByteArrayInputStream(new byte[]{1}), 1));
    }

    @Test public void redirectedUploadIsNotReplayedWithAnExhaustedStream() {
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setResponseCode(307).setHeader("Location", "/dav/canonical");
            }
        });
        assertThrows(IOException.class, () -> client.upload("file", new ByteArrayInputStream(new byte[]{1, 2}), 2));
        assertEquals(1, server.getRequestCount());
    }

    private static String quota(String href, String used, String available, String status) {
        return "<D:response><D:href>" + href + "</D:href><D:propstat><D:prop>"
                + "<D:quota-used-bytes>" + used + "</D:quota-used-bytes>"
                + "<D:quota-available-bytes>" + available + "</D:quota-available-bytes>"
                + "</D:prop><D:status>" + status + "</D:status></D:propstat></D:response>";
    }

    private void serveQuota(String body) {
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                return reply(207, "<D:multistatus xmlns:D='DAV:'>" + body + "</D:multistatus>");
            }
        });
    }

    @Test public void queryCollectionQuotaWithDepthZeroAndAuthentication() throws Exception {
        serveQuota(quota("/dav/", "75", "25", "HTTP/1.1 200 OK"));
        RemoteSpace space = client.space();
        assertEquals(100, space.totalBytes);
        assertEquals(75, space.usedBytes);
        assertEquals(25, space.freeBytes);
        RecordedRequest request = server.takeRequest();
        assertEquals("0", request.getHeader("Depth"));
        assertEquals("Basic dGVzdGVyOnNlY3JldA==", request.getHeader("Authorization"));
        assertTrue(request.getBody().readUtf8().contains("quota-available-bytes"));
    }

    @Test public void quotaIgnoresForeignChildAndFailedProperties() throws Exception {
        serveQuota(quota("http://foreign.invalid/dav/", "1", "2", "HTTP/1.1 200 OK")
                + quota("/dav/child", "1", "2", "HTTP/1.1 200 OK")
                + quota("/dav/", "1", "2", "HTTP/1.1 404 Not Found"));
        assertNull(client.space());
    }

    @Test public void missingUnlimitedOrOverflowedQuotaIsUnavailable() throws Exception {
        serveQuota(quota("/dav/", "", "-3", "HTTP/1.1 200 OK"));
        assertNull(client.space());
        serveQuota(quota("/dav/", String.valueOf(Long.MAX_VALUE), "1", "HTTP/1.1 200 OK"));
        assertNull(client.space());
        serveQuota(quota("/dav/", "", "25", "HTTP/1.1 200 OK"));
        RemoteSpace space = client.space();
        assertEquals(-1, space.totalBytes);
        assertEquals(25, space.freeBytes);
    }

    @Test public void quotaXmlHasTheSameEntitySafetyAsListings() {
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                return reply(207, "<!DOCTYPE x [<!ENTITY external SYSTEM 'file:///not-allowed'>]>"
                        + "<D:multistatus xmlns:D='DAV:'>&external;</D:multistatus>");
            }
        });
        assertThrows(IOException.class, client::space);
    }
}
