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
    private final java.util.concurrent.ExecutorService httpPool = java.util.concurrent.Executors.newFixedThreadPool(2);
    private AuraDb db;
    private AiRecommender ai;
    private SessionInspector inspector;
    private TvOverlayManager overlay;
    private SyncManager sync;
    private VoiceConcierge voice;

    @Override
    public void onCreate() {
        super.onCreate();
        db = AuraDb.getInstance(this);
        ai = new AiRecommender(db);
        inspector = new SessionInspector(this, new ComponentName(this, MediaProbeService.class), db, ai);
        overlay = new TvOverlayManager(this);
        sync = new SyncManager(db);
        voice = new VoiceConcierge(db, ai, sync);
        startHttpServer();
        startBackgroundMonitor();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startHttpServer();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        super.onDestroy();
    }

    private void startBackgroundMonitor() {
        new Thread(() -> {
            try { Thread.sleep(45000L); } catch (Exception ignored) {}
            while (running) {
                try {
                    runBackgroundTick();
                    Thread.sleep(300000L);
                } catch (Exception ignored) {}
            }
        }, "AuraBgMonitor").start();
    }

    private void runBackgroundTick() {
        inspector.recordActivePlaybackOnly();
    }

    private void startHttpServer() {
        if (running) return;
        running = true;
        new Thread(this::acceptLoop, "AuraHttpProbe").start();
    }

    private void acceptLoop() {
        try {
            serverSocket = new ServerSocket(8765);
            while (running) {
                Socket client = serverSocket.accept();
                httpPool.submit(() -> handleClient(client));
            }
        } catch (Exception ignored) {}
    }

    private void handleClient(Socket client) {
        try (Socket s = client) {
            s.setSoTimeout(25000);
            java.io.InputStream rawIn = s.getInputStream();
            String reqLine = readAsciiLine(rawIn);
            if (reqLine == null) return;
            String[] parts = reqLine.split(" ");
            String reqBody = readHttpRequestBody(rawIn, readContentLength(rawIn));
            routeAndWrite(s.getOutputStream(), parts.length > 0 ? parts[0] : "GET",
                parts.length > 1 ? parts[1] : "/", reqBody);
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
            if (b != '\r') bos.write(b);
        }
        if (b == -1 && bos.size() == 0) return null;
        return bos.toString("UTF-8");
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
        String prof = optParam(uri, "profile", AuraDb.DEFAULT_PROFILE);
        db.setSetting("active_profile", prof);
        writeHttpResponse(out, "application/json", "{\"ok\":true,\"active_profile\":\"" + prof + "\"}");
        return true;
    }

    private boolean handleConfigRoute(OutputStream out, Uri uri) throws Exception {
        String apiKey = optParam(uri, "gemini_api_key", "");
        String host = optParam(uri, "mac_host", "");
        String phone = optParam(uri, "phone_host", "");
        if (!apiKey.isEmpty()) db.setSetting("gemini_api_key", apiKey);
        if (!host.isEmpty()) db.setSetting("mac_host", host);
        if (!phone.isEmpty()) db.setSetting("phone_host", phone);
        writeHttpResponse(out, "application/json", "{\"ok\":true}");
        return true;
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
        return false;
    }

    private boolean handleToggleFilterRoute(OutputStream out, Uri uri) throws Exception {
        String prof = db.getSetting("active_profile", AuraDb.DEFAULT_PROFILE);
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
        String prof = req.optString("profile", db.getSetting("active_profile", AuraDb.DEFAULT_PROFILE));
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
        String prof = db.getSetting("active_profile", AuraDb.DEFAULT_PROFILE);
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
