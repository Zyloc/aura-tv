package com.aura.tvprobe;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.wifi.WifiManager;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Builds a glass-styled QR / remote-access info card.
 * Includes a minimal QR Code encoder (Model 2, EC level L, versions 1-4).
 */
public final class QrRemoteBuilder {

    private QrRemoteBuilder() { }

    // ── Public API ───────────────────────────────────────────────────────

    /** Builds the complete glass card with QR code and connection info. */
    public static View buildQrCard(Context ctx, String tvIpAddress, int port) {
        return buildQrCard(ctx, tvIpAddress, port, "");
    }

    public static View buildQrCard(Context ctx, String tvIpAddress, int port, String publicUrl) {
        String qrUrl = "http://" + tvIpAddress + ":" + port;
        String primaryLabel = "http://mitv.local:" + port + "  \u00B7  http://mitv:" + port;
        String sslipHost = tvIpAddress.replace('.', '-') + ".sslip.io";
        String subText = qrUrl + "  \u00B7  http://" + sslipHost + ":" + port;
        return assembleQrCard(ctx, qrUrl, primaryLabel, subText);
    }

    private static View assembleQrCard(Context ctx, String qrTarget, String label, String subText) {
        LinearLayout card = createCardContainer(ctx);
        card.addView(createTitle(ctx));
        card.addView(createQrImageView(ctx, qrTarget));
        card.addView(createUrlLabel(ctx, label));
        card.addView(createSubtitleText(ctx, subText));
        card.addView(createInstructions(ctx));
        return card;
    }

    private static final java.util.concurrent.atomic.AtomicBoolean sNudging =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public static void nudgeLanArpTable(String tvIp) {
        if (!sNudging.compareAndSet(false, true)) return;
        new Thread(() -> runCrossBandArpNudge(tvIp), "AuraArpNudge").start();
    }

    private static void runCrossBandArpNudge(String tvIp) {
        try {
            for (String peerIp : buildCrossBandPeers(tvIp)) pingSingleHostQuietly(peerIp);
            nudgeIpv6NeighborsQuietly();
        } finally {
            sNudging.set(false);
        }
    }

    private static void nudgeIpv6NeighborsQuietly() {
        for (String v6 : getLiveIpv6Peers()) pingIpv6HostQuietly(v6);
    }

    private static java.util.List<String> getLiveIpv6Peers() {
        java.util.List<String> peers = new java.util.ArrayList<>();
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/ip", "-6", "neigh", "show", "dev", "wlan0"});
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
            String line;
            while ((line = br.readLine()) != null) addIpv6PeerFromLine(line, peers);
        } catch (Throwable ignored) {}
        return peers;
    }

    private static void addIpv6PeerFromLine(String line, java.util.List<String> peers) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length > 0 && parts[0].startsWith("fe80:")) peers.add(parts[0]);
    }

    private static void pingIpv6HostQuietly(String v6) {
        try {
            Runtime.getRuntime().exec(new String[]{"/system/bin/ping6", "-c", "1", "-W", "1", v6 + "%wlan0"}).waitFor();
        } catch (Throwable ignored) {}
    }

    private static java.util.Set<String> buildCrossBandPeers(String tvIp) {
        java.util.Set<String> peers = new java.util.LinkedHashSet<>(getLiveArpPeers());
        String prefix = extractSubnetPrefix(tvIp);
        if (!prefix.isEmpty()) addCommonLanHosts(peers, prefix, tvIp);
        return peers;
    }

    private static String extractSubnetPrefix(String ip) {
        int dot = ip != null ? ip.lastIndexOf('.') : -1;
        return dot > 0 ? ip.substring(0, dot + 1) : "";
    }

    private static void addCommonLanHosts(java.util.Set<String> peers, String prefix, String tvIp) {
        int[] hosts = {1, 2, 3, 5, 6, 7, 8};
        for (int h : hosts) {
            String candidate = prefix + h;
            if (!candidate.equals(tvIp)) peers.add(candidate);
        }
    }

    public static java.util.List<String> getLiveArpPeers() {
        java.util.List<String> peers = new java.util.ArrayList<>();
        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader("/proc/net/arp"))) {
            String line;
            while ((line = br.readLine()) != null) addLiveArpPeerFromLine(line, peers);
        } catch (Throwable ignored) {}
        return peers;
    }

    private static void addLiveArpPeerFromLine(String line, java.util.List<String> peers) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length >= 4 && parts[0].contains(".") && !"IP".equalsIgnoreCase(parts[0])) {
            peers.add(parts[0]);
        }
    }

    private static void pingSingleHostQuietly(String ip) {
        try {
            Runtime.getRuntime().exec(new String[]{"/system/bin/ping", "-c", "1", "-W", "1", ip}).waitFor();
        } catch (Throwable ignored) {}
    }

    /** Returns the device's WiFi or LAN IPv4 address as a dotted string. */
    public static String getTvIpAddress(Context ctx) {
        String wifiIp = getWifiManagerIp(ctx);
        if (isValidLanIp(wifiIp)) return wifiIp;
        String ifaceIp = scanNetworkInterfacesIp();
        return isValidLanIp(ifaceIp) ? ifaceIp : "0.0.0.0";
    }

    private static String getWifiManagerIp(Context ctx) {
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            return wm != null ? formatIp(wm.getConnectionInfo().getIpAddress()) : "0.0.0.0";
        } catch (Throwable e) { return "0.0.0.0"; }
    }

    private static boolean isValidLanIp(String ip) {
        return ip != null && !ip.isEmpty() && !"0.0.0.0".equals(ip) && !ip.startsWith("127.");
    }

    private static String scanNetworkInterfacesIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> en = java.net.NetworkInterface.getNetworkInterfaces();
            while (en != null && en.hasMoreElements()) {
                String found = findIpv4OnInterface(en.nextElement());
                if (isValidLanIp(found)) return found;
            }
        } catch (Throwable ignored) {}
        return "0.0.0.0";
    }

    private static String findIpv4OnInterface(java.net.NetworkInterface nif) throws Exception {
        if (nif == null || nif.isLoopback() || !nif.isUp()) return "";
        java.util.Enumeration<java.net.InetAddress> addrs = nif.getInetAddresses();
        while (addrs.hasMoreElements()) {
            java.net.InetAddress a = addrs.nextElement();
            if (!a.isLoopbackAddress() && a instanceof java.net.Inet4Address) return a.getHostAddress();
        }
        return "";
    }

    // ── Card layout pieces ───────────────────────────────────────────────

    private static LinearLayout createCardContainer(Context ctx) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = GlassTheme.dp(ctx, 24);
        card.setPadding(pad, pad, pad, pad);
        card.setBackground(GlassTheme.glassCard(GlassTheme.dp(ctx, 16)));
        card.setFocusable(true);
        return card;
    }

    private static TextView createTitle(Context ctx) {
        TextView t = GlassTheme.makeText(ctx, "\uD83D\uDCF1 Mobile Remote",
                18f, GlassTheme.TEXT_PRIMARY, GlassTheme.FONT_BOLD, 1);
        setBottomMargin(ctx, t, 16);
        return t;
    }

    private static ImageView createQrImageView(Context ctx, String url) {
        int size = GlassTheme.dp(ctx, 220);
        ImageView iv = new ImageView(ctx);
        iv.setImageBitmap(renderQrBitmap(url, size));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = GlassTheme.dp(ctx, 14);
        iv.setLayoutParams(lp);
        return iv;
    }

    private static TextView createUrlLabel(Context ctx, String url) {
        TextView t = GlassTheme.makeText(ctx, url,
                14f, GlassTheme.ACCENT, GlassTheme.FONT_MEDIUM, 1);
        setBottomMargin(ctx, t, 6);
        return t;
    }

    private static TextView createSubtitleText(Context ctx, String text) {
        TextView t = GlassTheme.makeText(ctx, text,
                13f, GlassTheme.TEXT_SECONDARY, GlassTheme.FONT_REGULAR, 1);
        setBottomMargin(ctx, t, 10);
        return t;
    }

    private static TextView createInstructions(Context ctx) {
        return GlassTheme.makeMuted(ctx,
                "Enter API keys, search, manage profiles");
    }

    private static void setBottomMargin(Context ctx, View v, int dp) {
        LinearLayout.LayoutParams lp = GlassTheme.matchWrapParams();
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = GlassTheme.dp(ctx, dp);
        v.setLayoutParams(lp);
    }

    // ── IP formatting ────────────────────────────────────────────────────

    private static String formatIp(int ip) {
        return (ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "."
                + ((ip >> 16) & 0xFF) + "." + ((ip >> 24) & 0xFF);
    }

    // ── Minimal QR encoder (Model 2, EC-L, Byte mode, v1-4) ─────────────

    /** Renders a QR code bitmap at the requested pixel size. */
    static Bitmap renderQrBitmap(String data, int sizePx) {
        boolean[][] matrix = encode(data);
        if (matrix == null) return createFallbackBitmap(data, sizePx);
        return matrixToBitmap(matrix, sizePx);
    }

    private static Bitmap matrixToBitmap(boolean[][] matrix, int sizePx) {
        int modules = matrix.length;
        int cellSize = sizePx / (modules + 8);
        int margin = (sizePx - cellSize * modules) / 2;
        Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(Color.WHITE);
        drawModules(c, matrix, createDarkPaint(), cellSize, margin);
        return bmp;
    }

    private static Paint createDarkPaint() {
        Paint dark = new Paint();
        dark.setColor(Color.BLACK);
        dark.setStyle(Paint.Style.FILL);
        return dark;
    }

    private static void drawModules(Canvas c, boolean[][] m,
                                    Paint paint, int cell, int margin) {
        for (int r = 0; r < m.length; r++)
            for (int col = 0; col < m.length; col++)
                if (m[r][col])
                    c.drawRect(margin + col * cell, margin + r * cell,
                            margin + (col + 1) * cell,
                            margin + (r + 1) * cell, paint);
    }

    private static Bitmap createFallbackBitmap(String data, int sizePx) {
        Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(Color.WHITE);
        Paint p = createFallbackPaint(sizePx);
        c.drawText(data, sizePx / 2f, sizePx / 2f, p);
        return bmp;
    }

    private static Paint createFallbackPaint(int sizePx) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.BLACK);
        p.setTextSize(sizePx / 12f);
        p.setTextAlign(Paint.Align.CENTER);
        return p;
    }

    // ── QR encoding core ─────────────────────────────────────────────────

    // Version capacities for byte mode, EC level L
    private static final int[] BYTE_CAPS = {0, 17, 32, 53, 78};

    static boolean[][] encode(String data) {
        byte[] bytes = data.getBytes();
        int version = pickVersion(bytes.length);
        if (version < 0) return null;
        int size = 17 + version * 4;
        boolean[][] grid = new boolean[size][size];
        boolean[][] locked = new boolean[size][size];
        initBasePatterns(grid, locked, version, size);
        populateAndMaskGrid(grid, locked, bytes, version, size);
        return grid;
    }

    private static void initBasePatterns(boolean[][] grid, boolean[][] locked, int version, int size) {
        placeFinderPatterns(grid, locked, size);
        placeAlignmentPattern(grid, locked, version, size);
        placeTimingPatterns(grid, locked, size);
        reserveFormatArea(locked, size);
    }

    private static void populateAndMaskGrid(boolean[][] grid, boolean[][] locked,
                                            byte[] bytes, int version, int size) {
        byte[] encoded = buildDataStream(bytes, version);
        byte[] ecBytes = computeEc(encoded, version);
        byte[] full = concat(encoded, ecBytes);
        placeDataBits(grid, locked, full, size);
        int mask = applyBestMask(grid, locked, size);
        placeFormatInfo(grid, size, mask);
    }

    private static int pickVersion(int len) {
        for (int v = 1; v <= 4; v++) if (len <= BYTE_CAPS[v]) return v;
        return -1;
    }

    // ── Finder patterns ──────────────────────────────────────────────────

    private static void placeFinderPatterns(boolean[][] g,
                                            boolean[][] l, int s) {
        placeFinderAt(g, l, 0, 0, s);
        placeFinderAt(g, l, 0, s - 7, s);
        placeFinderAt(g, l, s - 7, 0, s);
    }

    private static void placeFinderAt(boolean[][] g, boolean[][] l,
                                      int row, int col, int sz) {
        for (int r = -1; r <= 7; r++)
            for (int c = -1; c <= 7; c++)
                setIfInBounds(g, l, row + r, col + c, sz,
                        finderModule(r, c));
    }

    private static boolean finderModule(int r, int c) {
        if (r < 0 || r > 6 || c < 0 || c > 6) return false;
        if (r == 0 || r == 6) return c >= 0 && c <= 6;
        if (c == 0 || c == 6) return true;
        return r >= 2 && r <= 4 && c >= 2 && c <= 4;
    }

    // ── Alignment pattern (v2+) ──────────────────────────────────────────

    private static final int[] ALIGN_POS = {0, 0, 18, 22, 26};

    private static void placeAlignmentPattern(boolean[][] g,
                                              boolean[][] l, int v, int s) {
        if (v < 2) return;
        int center = ALIGN_POS[v];
        for (int r = -2; r <= 2; r++)
            for (int c = -2; c <= 2; c++)
                setCell(g, l, center + r, center + c, s,
                        alignModule(r, c));
    }

    private static boolean alignModule(int r, int c) {
        return Math.abs(r) == 2 || Math.abs(c) == 2
                || (r == 0 && c == 0);
    }

    // ── Timing patterns ──────────────────────────────────────────────────

    private static void placeTimingPatterns(boolean[][] g,
                                            boolean[][] l, int s) {
        for (int i = 8; i < s - 8; i++) {
            boolean on = (i % 2 == 0);
            setCell(g, l, 6, i, s, on);
            setCell(g, l, i, 6, s, on);
        }
    }

    // ── Format area reservation ──────────────────────────────────────────

    private static void reserveFormatArea(boolean[][] l, int s) {
        for (int i = 0; i <= 8; i++) {
            lockCell(l, 8, i, s);
            lockCell(l, i, 8, s);
        }
        for (int i = 0; i < 8; i++) {
            lockCell(l, 8, s - 1 - i, s);
            lockCell(l, s - 1 - i, 8, s);
        }
    }

    // ── Data stream building (byte mode) ─────────────────────────────────

    // Total data codewords per version (EC-L)
    private static final int[] DATA_CW = {0, 19, 34, 55, 80};

    private static byte[] buildDataStream(byte[] data, int version) {
        int totalBits = DATA_CW[version] * 8;
        int[] bits = new int[totalBits];
        int pos = writePayloadBits(bits, data, version);
        if (pos + 4 <= totalBits) pos = writeBits(bits, pos, 0, 4);
        pos = padToByteAlign(bits, pos);
        padCodewords(bits, pos, totalBits);
        return bitsToBytes(bits, totalBits);
    }

    private static int writePayloadBits(int[] bits, byte[] data, int version) {
        int pos = writeBits(bits, 0, 0b0100, 4);          // byte mode
        int lenBits = (version <= 9) ? 8 : 16;
        pos = writeBits(bits, pos, data.length, lenBits); // length
        for (byte b : data) pos = writeBits(bits, pos, b & 0xFF, 8);
        return pos;
    }

    private static int padToByteAlign(int[] bits, int pos) {
        while (pos % 8 != 0 && pos < bits.length) bits[pos++] = 0;
        return pos;
    }

    private static int padCodewords(int[] bits, int pos, int total) {
        boolean alt = false;
        while (pos < total) {
            pos = writeBits(bits, pos, alt ? 0x11 : 0xEC, 8);
            alt = !alt;
        }
        return pos;
    }

    // ── Error correction (Reed-Solomon, EC-L) ────────────────────────────

    // EC codeword counts per version (EC-L)
    private static final int[] EC_CW = {0, 7, 10, 15, 20};

    private static byte[] computeEc(byte[] data, int version) {
        int ecLen = EC_CW[version];
        int[] gen = rsGeneratorPoly(ecLen);
        int[] msg = new int[data.length + ecLen];
        for (int i = 0; i < data.length; i++) msg[i] = data[i] & 0xFF;
        rsPolyDivide(msg, gen, data.length, ecLen);
        byte[] ec = new byte[ecLen];
        for (int i = 0; i < ecLen; i++) ec[i] = (byte) msg[data.length + i];
        return ec;
    }

    private static int[] rsGeneratorPoly(int degree) {
        int[] poly = new int[degree + 1];
        poly[0] = 1;
        for (int i = 0; i < degree; i++)
            poly = rsPolyMul(poly, i + 1, new int[]{1, gfExp(i)});
        return poly;
    }

    private static int[] rsPolyMul(int[] a, int aLen, int[] b) {
        int[] result = new int[aLen + b.length - 1];
        for (int i = 0; i < aLen; i++)
            for (int j = 0; j < b.length; j++)
                result[i + j] ^= gfMul(a[i], b[j]);
        return result;
    }

    private static void rsPolyDivide(int[] msg, int[] gen,
                                     int dataLen, int ecLen) {
        for (int i = 0; i < dataLen; i++) {
            int coef = msg[i];
            if (coef == 0) continue;
            for (int j = 1; j < gen.length; j++)
                msg[i + j] ^= gfMul(gen[j], coef);
        }
    }

    // ── Galois Field GF(256) with 0x11D ──────────────────────────────────

    private static final int[] GF_EXP = new int[256];
    private static final int[] GF_LOG = new int[256];

    static {
        initGaloisTables();
    }

    private static void initGaloisTables() {
        int val = 1;
        for (int i = 0; i < 255; i++) {
            GF_EXP[i] = val;
            GF_LOG[val] = i;
            val = (val << 1) ^ ((val >= 128) ? 0x11D : 0);
        }
        GF_EXP[255] = GF_EXP[0];
    }

    private static int gfExp(int a) { return GF_EXP[a % 255]; }

    private static int gfMul(int a, int b) {
        if (a == 0 || b == 0) return 0;
        return GF_EXP[(GF_LOG[a] + GF_LOG[b]) % 255];
    }

    // ── Data placement ───────────────────────────────────────────────────

    private static void placeDataBits(boolean[][] g, boolean[][] l,
                                      byte[] data, int s) {
        int bitIdx = 0, totalBits = data.length * 8, col = s - 1;
        boolean upward = true;
        while (col > 0) {
            if (col == 6) col--;
            bitIdx = placeColumnPair(g, l, data, s, col, upward, bitIdx, totalBits);
            upward = !upward;
            col -= 2;
        }
    }

    private static int placeColumnPair(boolean[][] g, boolean[][] l,
                                       byte[] data, int s, int col,
                                       boolean upward, int start, int total) {
        int bitIdx = start;
        for (int i = 0; i < s; i++) {
            int row = upward ? (s - 1 - i) : i;
            bitIdx = placeRowPair(g, l, data, s, row, col, bitIdx, total);
        }
        return bitIdx;
    }

    private static int placeRowPair(boolean[][] g, boolean[][] l, byte[] data,
                                    int s, int row, int col, int bitIdx, int total) {
        for (int dc = 0; dc <= 1; dc++) {
            int c = col - dc;
            if (c < 0 || c >= s || l[row][c]) continue;
            if (bitIdx < total) {
                g[row][c] = ((data[bitIdx / 8] >> (7 - bitIdx % 8)) & 1) == 1;
            }
            bitIdx++;
        }
        return bitIdx;
    }

    // ── Masking ──────────────────────────────────────────────────────────

    private static int applyBestMask(boolean[][] g, boolean[][] l, int s) {
        applyMask(g, l, s, 0);
        return 0;
    }

    private static void applyMask(boolean[][] g, boolean[][] l,
                                  int s, int mask) {
        for (int r = 0; r < s; r++)
            for (int c = 0; c < s; c++)
                if (!l[r][c] && maskCondition(mask, r, c))
                    g[r][c] = !g[r][c];
    }

    private static boolean maskCondition(int mask, int r, int c) {
        return (r + c) % 2 == 0;
    }

    // ── Format info (EC-L, mask 0) ───────────────────────────────────────

    // Pre-computed for EC-L (01) + mask 0 (000) → data 01000 → format bits
    private static final int FORMAT_BITS = 0x77C4;

    private static void placeFormatInfo(boolean[][] g, int s, int mask) {
        int bits = FORMAT_BITS;
        placeFormatAround(g, s, bits);
        g[s - 8][8] = true;  // dark module
    }

    private static void placeFormatAround(boolean[][] g, int s, int bits) {
        for (int i = 0; i <= 5; i++) g[8][i] = bit(bits, 14 - i);
        g[8][7] = bit(bits, 8);
        g[8][8] = bit(bits, 7);
        g[7][8] = bit(bits, 6);
        for (int i = 0; i <= 5; i++) g[i][8] = bit(bits, i);
        for (int i = 0; i < 7; i++) g[s - 1 - i][8] = bit(bits, 14 - i);
        for (int i = 0; i < 8; i++) g[8][s - 8 + i] = bit(bits, 7 - i);
    }

    private static boolean bit(int val, int i) {
        return ((val >> i) & 1) == 1;
    }

    // ── Bit-level utilities ──────────────────────────────────────────────

    private static int writeBits(int[] arr, int pos, int val, int len) {
        for (int i = len - 1; i >= 0; i--)
            arr[pos++] = (val >> i) & 1;
        return pos;
    }

    private static byte[] bitsToBytes(int[] bits, int totalBits) {
        byte[] out = new byte[totalBits / 8];
        for (int i = 0; i < out.length; i++)
            for (int b = 0; b < 8; b++)
                out[i] |= (bits[i * 8 + b] << (7 - b));
        return out;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    // ── Grid helpers ─────────────────────────────────────────────────────

    private static void setIfInBounds(boolean[][] g, boolean[][] l,
                                      int r, int c, int s, boolean v) {
        if (r >= 0 && r < s && c >= 0 && c < s) {
            g[r][c] = v;
            l[r][c] = true;
        }
    }

    private static void setCell(boolean[][] g, boolean[][] l,
                                int r, int c, int s, boolean v) {
        if (r >= 0 && r < s && c >= 0 && c < s) {
            g[r][c] = v;
            l[r][c] = true;
        }
    }

    private static void lockCell(boolean[][] l, int r, int c, int s) {
        if (r >= 0 && r < s && c >= 0 && c < s) l[r][c] = true;
    }
}
