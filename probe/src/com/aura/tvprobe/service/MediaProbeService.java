package com.aura.tvprobe;

import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.service.notification.NotificationListenerService;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class MediaProbeService extends NotificationListenerService {
    private ServerSocket serverSocket;
    private volatile boolean running = false;
    private final java.util.concurrent.ExecutorService httpPool = java.util.concurrent.Executors.newCachedThreadPool();
    private AuraDb db;
    private AiRecommender ai;
    private SessionInspector inspector;
    private MediaEventWatcher eventWatcher;
    private TvOverlayManager overlay;
    private SyncManager sync;
    private VoiceConcierge voice;

    @Override
    public void onCreate() {
        super.onCreate();
        initCoreDependencies();
        startHttpServer();
        eventWatcher.registerAll();
        startHourlyRecoMonitor();
    }

    private void initCoreDependencies() {
        db = AuraDb.getInstance(this);
        ai = new AiRecommender(db);
        ComponentName comp = new ComponentName(this, MediaProbeService.class);
        inspector = new SessionInspector(this, comp, db, ai);
        eventWatcher = new MediaEventWatcher(this, comp, db, inspector);
        overlay = new TvOverlayManager(this);
        sync = new SyncManager(db);
        voice = new VoiceConcierge(db, ai, sync);
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        if (eventWatcher != null) eventWatcher.registerAll();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try { requestRebind(new ComponentName(this, MediaProbeService.class)); } catch (Throwable ignored) {}
        if (eventWatcher != null) eventWatcher.registerAll();
        startHttpServer();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (eventWatcher != null) eventWatcher.unregisterAll();
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        super.onDestroy();
    }

    private void startHourlyRecoMonitor() {
        new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(3600000L);
                    refreshHourlyRecosIfNeeded();
                } catch (Exception ignored) {}
            }
        }, "AuraHourlyReco").start();
    }

    private void refreshHourlyRecosIfNeeded() {
        String prof = db.getValidActiveProfile();
        if (!db.isRecoCacheFresh(prof)) {
            inspector.syncAndBuildState(true);
        }
    }

    private synchronized void startHttpServer() {
        LocalDomainResponder.ensureStarted(this);
        if (db != null) db.setSetting("remote_url", "");
        if (running && serverSocket != null && !serverSocket.isClosed() && serverSocket.isBound()) return;
        running = true;
        new Thread(() -> acceptLoop(8765, true), "AuraHttpProbe").start();
        new Thread(() -> acceptLoop(8080, false), "AuraHttp8080").start();
        new Thread(this::runLanArpKeepAliveLoop, "AuraLanArpKeepAlive").start();
    }

    private void runLanArpKeepAliveLoop() {
        while (running) {
            try {
                QrRemoteBuilder.nudgeLanArpTable(QrRemoteBuilder.getTvIpAddress(this));
                LocalDomainResponder.announceAllDomainsNow(this);
                Thread.sleep(60000L);
            } catch (Exception ignored) {}
        }
    }

    private void acceptLoop(int port, boolean isPrimary) {
        while (running) {
            runServerSocketOnce(port, isPrimary);
            if (running) try { Thread.sleep(1000L); } catch (InterruptedException ignored) {}
        }
    }

    private void runServerSocketOnce(int port, boolean isPrimary) {
        ServerSocket ss = null;
        try {
            ss = openBoundServerSocket(port, isPrimary);
            acceptClientsUntilClosed(ss);
        } catch (Exception ignored) {
        } finally {
            closeSocketQuietly(ss);
        }
    }

    private void acceptClientsUntilClosed(ServerSocket ss) throws Exception {
        while (running && ss != null && !ss.isClosed()) {
            Socket client = ss.accept();
            httpPool.submit(() -> handleClient(client));
        }
    }

    private ServerSocket openBoundServerSocket(int port, boolean isPrimary) throws Exception {
        if (isPrimary) closeSocketQuietly(serverSocket);
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new java.net.InetSocketAddress("0.0.0.0", port), 50);
        if (isPrimary) serverSocket = ss;
        return ss;
    }

    private void closeSocketQuietly(ServerSocket ss) {
        try { if (ss != null) ss.close(); } catch (Exception ignored) {}
    }

    private void handleClient(Socket client) {
        try (Socket s = client) {
            s.setSoTimeout(90000);
            s.setTcpNoDelay(true);
            java.io.InputStream rawIn = s.getInputStream();
            String reqLine = readAsciiLine(rawIn);
            if (reqLine == null) { closeNonHttpSocket(s); return; }
            processHttpRequest(s, rawIn, reqLine);
        } catch (Exception e) {
            android.util.Log.w("AuraHttp", "Client err: " + e.getMessage());
        }
    }

    private void closeNonHttpSocket(Socket s) {
        android.util.Log.i("AuraHttp", s.getRemoteSocketAddress() + " -> [TLS/Empty rejected]");
        try {
            s.getOutputStream().write(new byte[]{0x15, 0x03, 0x03, 0x00, 0x02, 0x02, 0x28});
            s.getOutputStream().flush();
            s.setSoLinger(true, 0);
        } catch (Exception ignored) {}
    }

    private void processHttpRequest(Socket s, java.io.InputStream rawIn, String reqLine) throws Exception {
        android.util.Log.i("AuraHttp", s.getRemoteSocketAddress() + " -> " + reqLine);
        String[] parts = reqLine.split(" ");
        String reqBody = readHttpRequestBody(rawIn, readContentLength(rawIn));
        routeAndWrite(s.getOutputStream(), parts.length > 0 ? parts[0] : "GET",
            parts.length > 1 ? parts[1] : "/", reqBody);
        finishSocketCleanly(s, rawIn, 2500);
    }

    private void finishSocketCleanly(Socket s, java.io.InputStream rawIn, int drainMs) {
        try {
            s.shutdownOutput();
            s.setSoTimeout(drainMs);
            byte[] buf = new byte[512];
            while (rawIn.read(buf) != -1) {}
        } catch (Exception ignored) {}
    }

    private int readContentLength(java.io.InputStream rawIn) throws Exception {
        int contentLen = 0;
        String hdr;
        while ((hdr = readAsciiLine(rawIn)) != null && !hdr.isEmpty()) {
            if (hdr.toLowerCase().startsWith("content-length:")) {
                contentLen = Integer.parseInt(hdr.substring(15).trim());
            }
        }
        return contentLen;
    }

    private String readHttpRequestBody(java.io.InputStream rawIn, int contentLen) throws Exception {
        if (contentLen <= 0) return "";
        byte[] buf = new byte[contentLen];
        int read = 0;
        while (read < contentLen) {
            int n = rawIn.read(buf, read, contentLen - read);
            if (n == -1) break;
            read += n;
        }
        return new String(buf, 0, read, StandardCharsets.UTF_8);
    }

    private String readAsciiLine(java.io.InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(128);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (bos.size() == 0 && (b == 0x16 || (b < 0x20 && b != '\r' && b != '\t'))) return null;
            if (b != '\r') bos.write(b);
            if (bos.size() > 4096) return null;
        }
        return (b == -1 && bos.size() == 0) ? null : bos.toString("UTF-8");
    }

    private void routeAndWrite(OutputStream out, String method, String fullPath, String reqBody) throws Exception {
        Uri uri = Uri.parse("http://localhost" + fullPath);
        String p = uri.getPath();
        if (tryRouteStaticOrConfig(out, uri, p)) return;
        if (tryRouteUserActions(out, uri, p, reqBody)) return;
        if (tryRouteOverlayOrState(out, method, uri, p, reqBody)) return;
        JSONObject raw = inspector.inspectAll();
        writeHttpResponse(out, "application/json", raw.toString());
    }

    private boolean tryRouteStaticOrConfig(OutputStream out, Uri uri, String p) throws Exception {
        if ("/".equals(p) || "/index.html".equals(p)) {
            writeHttpResponse(out, "text/html; charset=utf-8", WebUiHtml.PAGE);
            return true;
        }
        if ("/favicon.ico".equals(p)) { writeHttpResponse(out, "image/x-icon", ""); return true; }
        if ("/open-ui".equals(p)) return handleOpenUiRoute(out);
        if ("/api/set-profile".equals(p)) return handleSetProfileRoute(out, uri);
        if ("/api/config".equals(p)) return handleConfigRoute(out, uri);
        if ("/api/set-server".equals(p)) return handleSetServerRoute(out, uri);
        return false;
    }

    private boolean handleOpenUiRoute(OutputStream out) throws Exception {
        Intent i = new Intent(this, AuraActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        writeHttpResponse(out, "application/json", "{\"ok\":true}");
        return true;
    }

    private boolean handleSetProfileRoute(OutputStream out, Uri uri) throws Exception {
        String prof = optParam(uri, "profile", db.getValidActiveProfile());
        db.setSetting("active_profile", prof);
        writeHttpResponse(out, "application/json", "{\"ok\":true,\"active_profile\":\"" + prof + "\"}");
        return true;
    }

    private boolean handleConfigRoute(OutputStream out, Uri uri) throws Exception {
        saveOptionalSetting(uri, "gemini_api_key");
        saveOptionalSetting(uri, "mac_host");
        saveOptionalSetting(uri, "phone_host");
        saveOptionalSetting(uri, "remote_url");
        writeHttpResponse(out, "application/json", "{\"ok\":true}");
        return true;
    }

    private void saveOptionalSetting(Uri uri, String key) {
        String val = optParam(uri, key, "");
        if (!val.isEmpty()) db.setSetting(key, val);
    }

    private boolean handleSetServerRoute(OutputStream out, Uri uri) throws Exception {
        String mode = optParam(uri, "mode", "auto");
        db.setSetting("server_mode", mode);
        String host = optParam(uri, "host", "");
        if (!host.isEmpty()) db.setSetting("phone".equals(mode) ? "phone_host" : "mac_host", host);
        sync.syncWithHost();
        writeHttpResponse(out, "application/json", "{\"ok\":true,\"server_mode\":\"" + mode + "\"}");
        return true;
    }

    private boolean tryRouteUserActions(OutputStream out, Uri uri, String p, String reqBody) throws Exception {
        if ("/api/toggle-filter".equals(p)) return handleToggleFilterRoute(out, uri);
        if ("/api/watched".equals(p)) return handleWatchedRoute(out, uri, reqBody);
        if ("/api/chat".equals(p)) return handleChatRoute(out, uri, reqBody);
        if ("/api/catchup".equals(p)) return handleCatchupRoute(out, uri, reqBody);
        return false;
    }

    private boolean handleCatchupRoute(OutputStream out, Uri uri, String reqBody) throws Exception {
        JSONObject item = !reqBody.isEmpty() ? new JSONObject(reqBody) : new JSONObject();
        String prof = optParam(uri, "profile", item.optString("profile", db.getValidActiveProfile()));
        String q = optParam(uri, "q", item.optString("query", ""));
        populateCatchupItemFromUri(uri, item);
        JSONObject recap = ShowCompanionAgent.executeCompanionTurn(ai, db, prof, q, item);
        if (!"0".equals(optParam(uri, "speak", "1"))) {
            AuraTtsNarrator.speak(this, recap.optString("spoken_narration", ""), null);
        }
        writeHttpResponse(out, "application/json", recap.toString());
        return true;
    }

    private void populateCatchupItemFromUri(Uri uri, JSONObject item) throws Exception {
        String title = optParam(uri, "title", item.optString("title", ""));
        if (!title.isEmpty()) item.put("title", title).put("series_title", title);
        int s = Integer.parseInt(optParam(uri, "season", String.valueOf(item.optInt("season", 0))));
        int e = Integer.parseInt(optParam(uri, "episode", String.valueOf(item.optInt("episode", 0))));
        int pos = Integer.parseInt(optParam(uri, "pos", String.valueOf(item.optInt("position_sec", 0))));
        if (s > 0) item.put("season", s);
        if (e > 0) item.put("episode", e);
        if (pos > 0) item.put("position_sec", pos);
    }

    private boolean handleToggleFilterRoute(OutputStream out, Uri uri) throws Exception {
        String prof = db.getValidActiveProfile();
        String f = optParam(uri, "filter", "");
        if ("clear".equals(f)) db.clearFilters(prof);
        else if (!f.isEmpty()) db.toggleFilter(prof, f);
        new Thread(() -> sync.syncWithHost()).start();
        String filtersJson = new org.json.JSONArray(db.getActiveFilters(prof)).toString();
        writeHttpResponse(out, "application/json", "{\"ok\":true,\"active_filters\":" + filtersJson + "}");
        return true;
    }

    private boolean handleWatchedRoute(OutputStream out, Uri uri, String reqBody) throws Exception {
        JSONObject item = !reqBody.isEmpty() ? new JSONObject(reqBody) : new JSONObject();
        boolean w = !"0".equals(optParam(uri, "watched", item.optBoolean("watched", true) ? "1" : "0"));
        populateWatchedItemFromUri(uri, item);
        db.markTitleWatched(item, w);
        new Thread(() -> sync.syncWithHost()).start();
        writeHttpResponse(out, "application/json", "{\"ok\":true,\"watched\":" + w + "}");
        return true;
    }

    private void populateWatchedItemFromUri(Uri uri, JSONObject item) throws Exception {
        String title = optParam(uri, "title", item.optString("title"));
        if (!title.isEmpty()) item.put("title", title);
        String poster = optParam(uri, "poster_url", item.optString("poster_url"));
        if (!poster.isEmpty()) item.put("poster_url", poster);
        item.put("app", optParam(uri, "app", item.optString("app", "TV")));
    }

    private boolean handleChatRoute(OutputStream out, Uri uri, String reqBody) throws Exception {
        JSONObject req = !reqBody.isEmpty() ? new JSONObject(reqBody) : new JSONObject();
        String prof = req.optString("profile", db.getValidActiveProfile());
        String msg = req.optString("message", optParam(uri, "message", ""));
        boolean reset = req.optBoolean("reset", "1".equals(optParam(uri, "reset", "0")));
        writeHttpResponse(out, "application/json", voice.handleTurn(prof, msg, reset).toString());
        return true;
    }

    private boolean tryRouteOverlayOrState(OutputStream out, String method,
                                           Uri uri, String p, String reqBody) throws Exception {
        if ("/api/sync".equals(p) && "POST".equalsIgnoreCase(method)) return handleSyncRoute(out, reqBody);
        if ("/overlay".equals(p)) return handleOverlayRoute(out, uri);
        if ("/launch".equals(p)) return handleLaunchRoute(out, uri);
        if ("/api/state".equals(p)) return handleStateRoute(out, uri);
        return false;
    }

    private boolean handleSyncRoute(OutputStream out, String reqBody) throws Exception {
        String prof = db.getValidActiveProfile();
        if (!reqBody.isEmpty()) sync.ingestRemoteSync(new JSONObject(reqBody), prof);
        JSONObject resp = new JSONObject();
        resp.put("ok", true);
        resp.put("active_profile", prof);
        resp.put("history", db.listHistory(prof));
        resp.put("recommendations", db.getProfileRecommendations(prof));
        writeHttpResponse(out, "application/json", resp.toString());
        return true;
    }

    private boolean handleOverlayRoute(OutputStream out, Uri uri) throws Exception {
        overlay.showUpNextCard(
            optParam(uri, "badge", "Aura · Up Next"),
            optParam(uri, "title", ""),
            optParam(uri, "reason", ""),
            Integer.parseInt(optParam(uri, "sec", "8"))
        );
        writeHttpResponse(out, "application/json", "{\"ok\":true}");
        return true;
    }

    private boolean handleLaunchRoute(OutputStream out, Uri uri) throws Exception {
        boolean ok = overlay.launchDeepLink(optParam(uri, "uri", ""), optParam(uri, "pkg", ""));
        writeHttpResponse(out, "application/json", "{\"ok\":" + ok + "}");
        return true;
    }

    private boolean handleStateRoute(OutputStream out, Uri uri) throws Exception {
        boolean refresh = "1".equals(optParam(uri, "refresh", "0"));
        JSONObject st = refresh ? inspector.syncAndBuildState(true) : inspector.buildFastCachedState();
        writeHttpResponse(out, "application/json", st.toString());
        return true;
    }

    private String optParam(Uri uri, String key, String def) {
        String v = uri.getQueryParameter(key);
        return v != null ? v : def;
    }

    private void writeHttpResponse(OutputStream out, String contentType, String body) throws Exception {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: " + contentType + "\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Content-Length: " + payload.length + "\r\n" +
            "Connection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.UTF_8));
        out.write(payload);
        out.flush();
    }
}
