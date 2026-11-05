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
    private AuraDb db;
    private AiRecommender ai;
    private SessionInspector inspector;
    private TvOverlayManager overlay;
    private SyncManager sync;
    private VoiceConcierge voice;

    @Override
    public void onCreate() {
        super.onCreate();
        db = new AuraDb(this);
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
            while (running) {
                try {
                    inspector.syncAndBuildState(false);
                    sync.syncWithHost();
                    Thread.sleep(60000L);
                } catch (Exception ignored) {}
            }
        }, "AuraBgMonitor").start();
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
                new Thread(() -> handleClient(client)).start();
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
            String method = parts.length > 0 ? parts[0] : "GET";
            String path = parts.length > 1 ? parts[1] : "/";

            int contentLen = 0;
            String hdr;
            while ((hdr = readAsciiLine(rawIn)) != null && !hdr.isEmpty()) {
                if (hdr.toLowerCase().startsWith("content-length:")) {
                    contentLen = Integer.parseInt(hdr.substring(15).trim());
                }
            }
            String reqBody = "";
            if (contentLen > 0) {
                byte[] buf = new byte[contentLen];
                int read = 0;
                while (read < contentLen) {
                    int n = rawIn.read(buf, read, contentLen - read);
                    if (n == -1) break;
                    read += n;
                }
                reqBody = new String(buf, 0, read, StandardCharsets.UTF_8);
            }
            routeAndWrite(s.getOutputStream(), method, path, reqBody);
        } catch (Exception ignored) {}
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
        if ("/".equals(p) || "/index.html".equals(p)) {
            writeHttpResponse(out, "text/html; charset=utf-8", WebUiHtml.PAGE);
            return;
        }
        if ("/open-ui".equals(p)) {
            Intent i = new Intent(this, AuraActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            writeHttpResponse(out, "application/json", "{\"ok\":true}");
            return;
        }
        if ("/api/set-profile".equals(p)) {
            String prof = optParam(uri, "profile", AuraDb.DEFAULT_PROFILE);
            db.setSetting("active_profile", prof);
            writeHttpResponse(out, "application/json", "{\"ok\":true,\"active_profile\":\"" + prof + "\"}");
            return;
        }
        if ("/api/config".equals(p)) {
            String apiKey = optParam(uri, "gemini_api_key", "");
            String host = optParam(uri, "mac_host", "");
            String phone = optParam(uri, "phone_host", "");
            if (!apiKey.isEmpty()) db.setSetting("gemini_api_key", apiKey);
            if (!host.isEmpty()) db.setSetting("mac_host", host);
            if (!phone.isEmpty()) db.setSetting("phone_host", phone);
            writeHttpResponse(out, "application/json", "{\"ok\":true}");
            return;
        }
        if ("/api/set-server".equals(p)) {
            String mode = optParam(uri, "mode", "auto");
            db.setSetting("server_mode", mode);
            String host = optParam(uri, "host", "");
            if (!host.isEmpty()) db.setSetting("phone".equals(mode) ? "phone_host" : "mac_host", host);
            sync.syncWithHost();
            writeHttpResponse(out, "application/json", "{\"ok\":true,\"server_mode\":\"" + mode + "\"}");
            return;
        }
        if ("/api/toggle-filter".equals(p)) {
            String prof = db.getSetting("active_profile", AuraDb.DEFAULT_PROFILE);
            String f = optParam(uri, "filter", "");
            if ("clear".equals(f)) db.clearFilters(prof);
            else if (!f.isEmpty()) db.toggleFilter(prof, f);
            new Thread(() -> sync.syncWithHost()).start();
            writeHttpResponse(out, "application/json", "{\"ok\":true,\"active_filters\":" + new org.json.JSONArray(db.getActiveFilters(prof)).toString() + "}");
            return;
        }
        if ("/api/watched".equals(p)) {
            JSONObject item = !reqBody.isEmpty() ? new JSONObject(reqBody) : new JSONObject();
            String title = optParam(uri, "title", item.optString("title"));
            boolean w = !"0".equals(optParam(uri, "watched", item.optBoolean("watched", true) ? "1" : "0"));
            if (!title.isEmpty()) item.put("title", title);
            String poster = optParam(uri, "poster_url", item.optString("poster_url"));
            if (!poster.isEmpty()) item.put("poster_url", poster);
            String app = optParam(uri, "app", item.optString("app", "TV"));
            item.put("app", app);
            db.markTitleWatched(item, w);
            new Thread(() -> sync.syncWithHost()).start();
            writeHttpResponse(out, "application/json", "{\"ok\":true,\"watched\":" + w + "}");
            return;
        }
        if ("/api/chat".equals(p)) {
            String prof = db.getSetting("active_profile", AuraDb.DEFAULT_PROFILE);
            String msg = optParam(uri, "message", "");
            boolean reset = "1".equals(optParam(uri, "reset", "0"));
            if (!reqBody.isEmpty()) {
                JSONObject reqJson = new JSONObject(reqBody);
                msg = reqJson.optString("message", msg);
                reset = reqJson.optBoolean("reset", reset);
                prof = reqJson.optString("profile", prof);
            }
            JSONObject chatRes = voice.handleTurn(prof, msg, reset);
            writeHttpResponse(out, "application/json", chatRes.toString());
            return;
        }
        if ("/api/sync".equals(p) && "POST".equalsIgnoreCase(method)) {
            String prof = db.getSetting("active_profile", AuraDb.DEFAULT_PROFILE);
            if (!reqBody.isEmpty()) sync.ingestRemoteSync(new JSONObject(reqBody), prof);
            JSONObject resp = new JSONObject();
            resp.put("ok", true);
            resp.put("active_profile", prof);
            resp.put("history", db.listHistory(prof));
            resp.put("recommendations", db.getProfileRecommendations(prof));
            writeHttpResponse(out, "application/json", resp.toString());
            return;
        }
        if ("/overlay".equals(p)) {
            overlay.showUpNextCard(
                optParam(uri, "badge", "Aura · Up Next"),
                optParam(uri, "title", ""),
                optParam(uri, "reason", ""),
                Integer.parseInt(optParam(uri, "sec", "8"))
            );
            writeHttpResponse(out, "application/json", "{\"ok\":true}");
            return;
        }
        if ("/launch".equals(p)) {
            boolean ok = overlay.launchDeepLink(optParam(uri, "uri", ""), optParam(uri, "pkg", ""));
            writeHttpResponse(out, "application/json", "{\"ok\":" + ok + "}");
            return;
        }
        if ("/api/state".equals(p)) {
            String prof = optParam(uri, "profile", "");
            if (!prof.isEmpty()) db.setSetting("active_profile", prof);
            boolean refresh = "1".equals(optParam(uri, "refresh", "0"));
            JSONObject st = inspector.syncAndBuildState(refresh);
            writeHttpResponse(out, "application/json", st.toString());
            return;
        }
        JSONObject raw = inspector.inspectAll();
        writeHttpResponse(out, "application/json", raw.toString());
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
