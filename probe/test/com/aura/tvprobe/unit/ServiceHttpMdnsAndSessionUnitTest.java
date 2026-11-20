package com.aura.tvprobe;

import android.content.ComponentName;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import static org.junit.Assert.*;

/**
 * Unit tests for {@link SyncManager}, {@link LocalDomainResponder},
 * {@link SessionInspector}, and {@link MediaProbeService} HTTP routing.
 */
public class ServiceHttpMdnsAndSessionUnitTest {

    @Test
    public void testSyncManagerHostResolutionAndBidirectionalSync() throws Exception {
        InMemoryAuraDb db = new InMemoryAuraDb();
        SyncManager sync = new SyncManager(db);
        assertEquals("", sync.getTargetHostUrl());
        db.setSetting("phone_host", "http://192.168.1.50:8765");
        db.setSetting("mac_host", "http://192.168.1.60:8765");
        db.setSetting("server_mode", "phone");
        assertEquals("http://192.168.1.50:8765", sync.getTargetHostUrl());
        db.setSetting("server_mode", "mac");
        assertEquals("http://192.168.1.60:8765", sync.getTargetHostUrl());
        verifyLiveLoopbackSync(db, sync);
    }

    private void verifyLiveLoopbackSync(InMemoryAuraDb db, SyncManager sync) throws Exception {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/api/sync", ex -> writeMockSyncPayload(ex));
        srv.start();
        try {
            db.setSetting("mac_host", "http://127.0.0.1:" + srv.getAddress().getPort());
            assertTrue(sync.syncWithHost());
            assertEquals(1, db.listHistory("member1").length());
            assertEquals(8, db.getProfileRecommendations("member1").length());
        } finally { srv.stop(0); }
    }

    private void writeMockSyncPayload(com.sun.net.httpserver.HttpExchange ex) throws java.io.IOException {
        byte[] b = buildMockSyncJsonBytes();
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    private byte[] buildMockSyncJsonBytes() {
        try {
            JSONArray recos = new JSONArray();
            for (int i = 0; i < 8; i++) recos.put(new JSONObject().put("title", "Show " + i).put("year", 2024));
            return populateMockSyncRoot(recos).toString().getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) { return "{}".getBytes(StandardCharsets.UTF_8); }
    }

    private JSONObject populateMockSyncRoot(JSONArray recos) throws Exception {
        JSONObject hist = new JSONObject().put("media_key", "nf:silo").put("title", "Silo").put("position_sec", 600).put("duration_sec", 2700);
        return new JSONObject()
            .put("profile_labels", new JSONObject().put("member1", "Alex"))
            .put("watched_items", new JSONArray().put(new JSONObject().put("title", "Old Film")))
            .put("history", new JSONArray().put(hist)).put("recommendations", recos)
            .put("voice_session", new JSONObject().put("results", new JSONArray().put(new JSONObject().put("title", "Andor"))));
    }

    @Test
    public void testLocalDomainResponderDnsAndMdnsPacketBuilders() throws Exception {
        byte[] queryA = buildDnsQueryPacket("mitv", 1, true);
        byte[] queryAaaa = buildDnsQueryPacket("aura", 28, false);
        assertEquals("mitv", invokeMdns("findMatchingHostAlias", new Class<?>[]{byte[].class, int.class}, queryA, queryA.length));
        byte[] ip4 = new byte[]{(byte) 192, (byte) 168, 1, 4};
        byte[] replyA = (byte[]) invokeMdns("buildUnicastReply",
            new Class<?>[]{byte[].class, int.class, byte[].class, byte[].class}, queryA, queryA.length, ip4, null);
        assertNotNull(replyA);
        assertEquals(1, replyA[7]);
        verifyNoDataAndMulticastPackets(queryAaaa, ip4);
    }

    private void verifyNoDataAndMulticastPackets(byte[] queryAaaa, byte[] ip4) throws Exception {
        byte[] replyNoData = (byte[]) invokeMdns("buildUnicastReply",
            new Class<?>[]{byte[].class, int.class, byte[].class, byte[].class}, queryAaaa, queryAaaa.length, ip4, null);
        assertNotNull(replyNoData);
        assertEquals(0, replyNoData[7]);
        assertEquals(1, replyNoData[9]);
        byte[] mcast = (byte[]) invokeMdns("buildMulticastAnswer",
            new Class<?>[]{String.class, byte[].class, byte[].class}, "mitv", ip4, null);
        assertNotNull(mcast);
        assertTrue(mcast.length > 30);
    }

    private byte[] buildDnsQueryPacket(String host, int qType, boolean includeEdnsOpt) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(new byte[]{0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, (byte) (includeEdnsOpt ? 1 : 0)});
        bos.write(host.length());
        bos.write(host.getBytes(StandardCharsets.US_ASCII));
        bos.write(new byte[]{0x05, 'l', 'o', 'c', 'a', 'l', 0x00, 0x00, (byte) qType, 0x00, 0x01});
        if (includeEdnsOpt) bos.write(new byte[]{0, 0, 0x29, 0x10, 0, 0, 0, 0, 0, 0, 0});
        return bos.toByteArray();
    }

    private Object invokeMdns(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = LocalDomainResponder.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(null, args);
    }

    @Test
    public void testSessionInspectorStateTrendsAndSessionPersistence() throws Exception {
        InMemoryAuraDb db = new InMemoryAuraDb();
        TestableAiRecommender ai = new TestableAiRecommender(db);
        SessionInspector insp = new SessionInspector(new RecordingContext(),
            new ComponentName("com.aura.tvprobe", "MediaProbeService"), db, ai);
        assertTrue(SessionInspector.isOrphanEpisodeCodeTitle("", "S01E02 Half Loop"));
        assertFalse(SessionInspector.isOrphanEpisodeCodeTitle("Severance", "S01E02 Half Loop"));
        verifyTrendsAndSerializedSession(db, insp);
    }

    private void verifyTrendsAndSerializedSession(InMemoryAuraDb db, SessionInspector insp) throws Exception {
        JSONArray previews = new JSONArray().put(new JSONObject().put("package_name", "com.netflix.ninja")
            .put("title", "3 Body Problem").put("intent_uri", "http://nflx/trendingnow"));
        insp.ingestBroadcastedAppTrends(previews, new JSONArray(), Collections.emptyMap());
        assertEquals(1, db.getAppBroadcastTrends().length());
        insp.persistSerializedSession(buildAppleSessionPayload());
        assertEquals(1, db.listHistory("member1").length());
        assertTrue(insp.buildFastCachedState().getBoolean("online"));
    }

    private JSONObject buildAppleSessionPayload() throws Exception {
        JSONObject meta = new JSONObject().put("android.media.metadata.TITLE", "S1:E2 Half Loop")
            .put("android.media.metadata.ALBUM", "Severance").put("android.media.metadata.DURATION", 3000_000L);
        return new JSONObject().put("package", "com.apple.atve.androidtv.appletv")
            .put("state", 3).put("position_ms", 420_000L).put("metadata", meta);
    }

    @Test
    public void testMediaProbeServiceRoutesAndTlsRejection() throws Exception {
        MediaProbeService svc = buildWiredProbeService();
        verifyStaticAndConfigRoutes(svc);
        verifyActionAndStateRoutes(svc);
        verifyHandleClientOverLoopbackSockets(svc);
    }

    private void verifyStaticAndConfigRoutes(MediaProbeService svc) throws Exception {
        assertTrue(invokeRoute(svc, "GET", "/", "").contains("<!DOCTYPE html"));
        assertTrue(invokeRoute(svc, "GET", "/api/set-profile?profile=member1", "").contains("\"ok\":true"));
        assertTrue(invokeRoute(svc, "GET", "/api/config?gemini_api_key=k1&mac_host=http://127.0.0.1:9", "").contains("\"ok\":true"));
        assertTrue(invokeRoute(svc, "GET", "/api/toggle-filter?filter=scifi", "").contains("scifi"));
        assertTrue(invokeRoute(svc, "GET", "/api/toggle-filter?filter=clear", "").contains("[]"));
    }

    private void verifyActionAndStateRoutes(MediaProbeService svc) throws Exception {
        assertTrue(invokeRoute(svc, "POST", "/api/watched?title=Dark&watched=1", "{}").contains("\"watched\":true"));
        assertTrue(invokeRoute(svc, "POST", "/api/catchup?title=Severance&speak=0", "{}").contains("headline"));
        assertTrue(invokeRoute(svc, "POST", "/api/sync", "{\"history\":[]}").contains("\"ok\":true"));
        assertTrue(invokeRoute(svc, "GET", "/api/state?refresh=0", "").contains("\"online\":true"));
    }

    private MediaProbeService buildWiredProbeService() throws Exception {
        MediaProbeService svc = allocateProbeServiceWithoutStubSuper();
        InMemoryAuraDb db = new InMemoryAuraDb();
        TestableAiRecommender ai = new TestableAiRecommender(db);
        SyncManager sync = new SyncManager(db);
        setField(svc, "db", db);
        setField(svc, "ai", ai);
        setField(svc, "inspector", new SessionInspector(new RecordingContext(), null, db, ai));
        setField(svc, "sync", sync);
        setField(svc, "voice", new VoiceConcierge(db, ai, sync));
        return svc;
    }

    private MediaProbeService allocateProbeServiceWithoutStubSuper() throws Exception {
        Field uf = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        uf.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) uf.get(null);
        return (MediaProbeService) unsafe.allocateInstance(MediaProbeService.class);
    }

    private void setField(Object target, String name, Object val) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, val);
    }

    private String invokeRoute(MediaProbeService svc, String method, String path, String body) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Method m = MediaProbeService.class.getDeclaredMethod(
            "routeAndWrite", OutputStream.class, String.class, String.class, String.class);
        m.setAccessible(true);
        try { m.invoke(svc, out, method, path, body); }
        catch (java.lang.reflect.InvocationTargetException e) { throw new RuntimeException(e.getCause()); }
        return out.toString("UTF-8");
    }

    private void verifyHandleClientOverLoopbackSockets(MediaProbeService svc) throws Exception {
        Method handle = MediaProbeService.class.getDeclaredMethod("handleClient", Socket.class);
        handle.setAccessible(true);
        try (ServerSocket ss = new ServerSocket(0)) {
            byte[] tlsResp = sendRawBytesToHandleClient(svc, handle, ss, new byte[]{0x16, 0x03, 0x01});
            assertTrue(tlsResp.length == 0 || tlsResp[0] == 0x15);
            byte[] httpResp = sendRawBytesToHandleClient(svc, handle, ss,
                "GET /favicon.ico HTTP/1.1\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            assertTrue(new String(httpResp, StandardCharsets.UTF_8).startsWith("HTTP/1.1 200 OK"));
        }
    }

    private byte[] sendRawBytesToHandleClient(
            MediaProbeService svc, Method handle, ServerSocket ss, byte[] req) throws Exception {
        try (Socket client = new Socket("127.0.0.1", ss.getLocalPort());
             Socket serverSide = ss.accept()) {
            client.getOutputStream().write(req);
            client.shutdownOutput();
            handle.invoke(svc, serverSide);
            try { return client.getInputStream().readAllBytes(); }
            catch (java.net.SocketException resetByLinger) { return new byte[0]; }
        }
    }
}
