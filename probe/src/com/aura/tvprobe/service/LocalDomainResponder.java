package com.aura.tvprobe;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;

/**
 * Keeps TV Wi-Fi in high-performance mode and answers both RFC 6762 multicast mDNS
 * and RFC 1035 / RFC 6762 sec 6.7 unicast queries for mitv.local, aura.local, and mitv.
 */
public final class LocalDomainResponder {
    private static final String MCAST_IP4 = "224.0.0.251";
    private static final String MCAST_IP6 = "ff02::fb";
    private static final int MDNS_PORT = 5353;
    private static final String[] HOST_ALIASES = {"mitv", "aura", "auratv"};
    private static volatile boolean sRunning = false;
    private static WifiManager.WifiLock sWifiLock;
    private static WifiManager.MulticastLock sMcastLock;
    private static MulticastSocket sSocket;

    private LocalDomainResponder() {}

    public static synchronized void ensureStarted(Context ctx) {
        acquireLocksSafe(ctx.getApplicationContext());
        if (sRunning && sSocket != null && !sSocket.isClosed()) return;
        sRunning = true;
        new Thread(() -> runMdnsLoop(ctx.getApplicationContext()), "AuraMdnsResponder").start();
        registerNsdServiceSafe(ctx.getApplicationContext(), "mitv", 8765);
    }

    public static void announceAllDomainsNow(Context ctx) {
        byte[] ip4 = parseAddressBytes(QrRemoteBuilder.getTvIpAddress(ctx));
        if (ip4 == null || sSocket == null || sSocket.isClosed()) return;
        byte[] ip6 = getLinkLocalIpv6Bytes();
        for (String alias : HOST_ALIASES) {
            byte[] pkt = buildMulticastAnswer(alias, ip4, ip6);
            sendPacketSafe(sSocket, pkt, getInetAddressSafe(MCAST_IP4), MDNS_PORT);
            sendPacketSafe(sSocket, pkt, getInetAddressSafe(MCAST_IP6), MDNS_PORT);
        }
    }

    private static void acquireLocksSafe(Context appCtx) {
        try {
            WifiManager wm = (WifiManager) appCtx.getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return;
            ensureHighPerfWifiLock(wm);
            ensureMulticastLock(wm);
        } catch (Throwable ignored) {}
    }

    private static void ensureHighPerfWifiLock(WifiManager wm) {
        if (sWifiLock == null) {
            sWifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AuraHighPerfWifiLock");
            sWifiLock.setReferenceCounted(false);
        }
        if (!sWifiLock.isHeld()) sWifiLock.acquire();
    }

    private static void ensureMulticastLock(WifiManager wm) {
        if (sMcastLock == null) {
            sMcastLock = wm.createMulticastLock("AuraMdnsMulticastLock");
            sMcastLock.setReferenceCounted(false);
        }
        if (!sMcastLock.isHeld()) sMcastLock.acquire();
    }

    private static void runMdnsLoop(Context appCtx) {
        while (sRunning) {
            listenMdnsOnce(appCtx);
            sleepQuietly(1500L);
        }
    }

    private static void listenMdnsOnce(Context appCtx) {
        try (MulticastSocket ms = openMdnsSocket()) {
            sSocket = ms;
            announceAllDomainsNow(appCtx);
            receivePacketsLoop(appCtx, ms);
        } catch (Throwable ignored) {}
    }

    private static MulticastSocket openMdnsSocket() throws Exception {
        MulticastSocket ms = new MulticastSocket(null);
        ms.setReuseAddress(true);
        ms.bind(new InetSocketAddress(MDNS_PORT));
        ms.setTimeToLive(255);
        joinMulticastGroupSafe(ms);
        return ms;
    }

    private static void joinMulticastGroupSafe(MulticastSocket ms) {
        try {
            NetworkInterface wlan = NetworkInterface.getByName("wlan0");
            if (wlan != null) ms.setNetworkInterface(wlan);
            joinGroupOnWlan(ms, MCAST_IP4, wlan);
            joinGroupOnWlan(ms, MCAST_IP6, wlan);
        } catch (Throwable ignored) {}
    }

    private static void joinGroupOnWlan(MulticastSocket ms, String groupIp, NetworkInterface wlan) {
        try {
            InetAddress group = InetAddress.getByName(groupIp);
            if (wlan != null) ms.joinGroup(new InetSocketAddress(group, MDNS_PORT), wlan);
            else ms.joinGroup(group);
        } catch (Throwable ignored) {}
    }

    private static void receivePacketsLoop(Context appCtx, MulticastSocket ms) throws Exception {
        byte[] buf = new byte[1500];
        while (sRunning && !ms.isClosed()) {
            DatagramPacket pkt = new DatagramPacket(buf, buf.length);
            ms.receive(pkt);
            handleIncomingMdnsPacket(appCtx, ms, pkt);
        }
    }

    private static void handleIncomingMdnsPacket(Context appCtx, MulticastSocket ms, DatagramPacket pkt) {
        byte[] data = pkt.getData();
        int len = pkt.getLength();
        if (len < 17 || (data[2] & 0x80) != 0) return;
        String alias = findMatchingHostAlias(data, len);
        if (alias != null) replyToQuery(appCtx, ms, pkt, alias);
    }

    private static String findMatchingHostAlias(byte[] data, int len) {
        String lower = new String(data, 12, len - 12, StandardCharsets.ISO_8859_1).toLowerCase();
        for (String alias : HOST_ALIASES) {
            String p1 = ((char) alias.length()) + alias + "\u0005local";
            String p2 = ((char) alias.length()) + alias + "\u0000";
            if (lower.contains(p1) || lower.contains(p2)) return alias;
        }
        return null;
    }

    private static void replyToQuery(Context appCtx, MulticastSocket ms, DatagramPacket query, String alias) {
        byte[] ip4 = parseAddressBytes(QrRemoteBuilder.getTvIpAddress(appCtx));
        if (ip4 == null) return;
        byte[] ip6 = getLinkLocalIpv6Bytes();
        logIncomingQuery(query, alias);
        byte[] unicast = buildUnicastReply(query.getData(), query.getLength(), ip4, ip6);
        if (unicast != null) sendPacketSafe(ms, unicast, query.getAddress(), query.getPort());
        sendMulticastAnswers(ms, alias, ip4, ip6);
    }

    private static void logIncomingQuery(DatagramPacket q, String alias) {
        byte[] d = q.getData();
        int ne = findQuestionNameEnd(d, q.getLength());
        int qt = (ne > 0 && ne + 4 <= q.getLength()) ? (((d[ne] & 0xFF) << 8) | (d[ne + 1] & 0xFF)) : -1;
        int ar = ((d[10] & 0xFF) << 8) | (d[11] & 0xFF);
        android.util.Log.i("AuraMdns", "RECV " + q.getAddress() + ":" + q.getPort() + " alias=" + alias + " qType=" + qt + " arCount=" + ar);
    }

    private static void sendMulticastAnswers(MulticastSocket ms, String alias, byte[] ip4, byte[] ip6) {
        byte[] mcast = buildMulticastAnswer(alias, ip4, ip6);
        sendPacketSafe(ms, mcast, getInetAddressSafe(MCAST_IP4), MDNS_PORT);
        sendPacketSafe(ms, mcast, getInetAddressSafe(MCAST_IP6), MDNS_PORT);
    }

    private static byte[] buildUnicastReply(byte[] q, int len, byte[] ip4, byte[] ip6) {
        int nameEnd = findQuestionNameEnd(q, len);
        if (nameEnd < 0 || nameEnd + 4 > len) return null;
        int qType = ((q[nameEnd] & 0xFF) << 8) | (q[nameEnd + 1] & 0xFF);
        int optStart = findOptStart(q, len, nameEnd + 4);
        if (qType == 1 || qType == 255) return assembleUnicastAReply(q, len, nameEnd, optStart, ip4);
        return assembleUnicastNoDataReply(q, len, nameEnd, optStart);
    }

    private static int findOptStart(byte[] q, int len, int afterQuestion) {
        int arCount = ((q[10] & 0xFF) << 8) | (q[11] & 0xFF);
        if (arCount == 1 && len >= afterQuestion + 11 && q[afterQuestion] == 0
                && q[afterQuestion + 1] == 0 && q[afterQuestion + 2] == 0x29) {
            return afterQuestion;
        }
        return -1;
    }

    private static byte[] assembleUnicastAReply(byte[] q, int len, int nameEnd, int optStart, byte[] ip4) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(160);
            byte ar = (byte) (optStart > 0 ? 1 : 0);
            byte[] hdr = {q[0], q[1], (byte) (0x84 | (q[2] & 0x01)), 0x00, 0, 1, 0, 1, 0, 0, 0, ar};
            out.write(hdr);
            out.write(q, 12, (nameEnd + 4) - 12);
            writeUnicastRecord(out, q, nameEnd, 1, ip4);
            if (optStart > 0) out.write(q, optStart, len - optStart);
            return out.toByteArray();
        } catch (Throwable e) { return null; }
    }

    private static byte[] assembleUnicastNoDataReply(byte[] q, int len, int nameEnd, int optStart) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(160);
            byte ar = (byte) (optStart > 0 ? 1 : 0);
            byte[] hdr = {q[0], q[1], (byte) (0x84 | (q[2] & 0x01)), 0x00, 0, 1, 0, 0, 0, 1, 0, ar};
            out.write(hdr);
            out.write(q, 12, (nameEnd + 4) - 12);
            writeUnicastSoaAuthority(out, q, nameEnd);
            if (optStart > 0) out.write(q, optStart, len - optStart);
            return out.toByteArray();
        } catch (Throwable e) { return null; }
    }

    private static void writeUnicastSoaAuthority(ByteArrayOutputStream out, byte[] q, int nameEnd) throws Exception {
        out.write(q, 12, nameEnd - 12);
        byte[] soa = {0x00, 0x06, 0x00, 0x01, 0x00, 0x00, 0x00, 0x78, 0x00, 0x18,
            (byte) 0xC0, 0x0C, (byte) 0xC0, 0x0C, 0, 0, 0, 1, 0, 0, 0, 120, 0, 0, 0, 60, 0, 0, 0, 120, 0, 0, 0, 60};
        out.write(soa);
    }

    private static void writeUnicastRecord(ByteArrayOutputStream out, byte[] q, int nameEnd, int rrType, byte[] rdata) throws Exception {
        out.write(q, 12, nameEnd - 12);
        byte[] meta = {0x00, (byte) rrType, 0x00, 0x01, 0x00, 0x00, 0x00, 0x78, 0x00, (byte) rdata.length};
        out.write(meta);
        out.write(rdata);
    }

    private static int findQuestionNameEnd(byte[] q, int len) {
        int pos = 12;
        while (pos < len) {
            int b = q[pos] & 0xFF;
            if (b == 0) return pos + 1;
            if ((b & 0xC0) == 0xC0) return pos + 2;
            pos += 1 + b;
        }
        return -1;
    }

    private static byte[] buildMulticastAnswer(String alias, byte[] ip4, byte[] ip6) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(96);
            byte[] hdr = {0, 0, (byte) 0x84, 0x00, 0, 0, 0, 1, 0, 0, 0, 1};
            out.write(hdr);
            writeMcastDomainRecord(out, alias, 1, ip4);
            writeNsecRecord(out);
            return out.toByteArray();
        } catch (Throwable e) { return null; }
    }

    private static void writeNsecRecord(ByteArrayOutputStream out) throws Exception {
        byte[] nsec = {(byte) 0xC0, 0x0C, 0x00, 0x2F, (byte) 0x80, 0x01,
            0x00, 0x00, 0x00, 0x78, 0x00, 0x05, (byte) 0xC0, 0x0C, 0x00, 0x01, 0x40};
        out.write(nsec);
    }

    private static void writeMcastDomainRecord(ByteArrayOutputStream out, String alias, int rrType, byte[] rdata) throws Exception {
        byte[] label = alias.getBytes(StandardCharsets.US_ASCII);
        out.write(label.length);
        out.write(label);
        out.write(new byte[]{0x05, 'l', 'o', 'c', 'a', 'l', 0x00});
        out.write(new byte[]{0x00, (byte) rrType, (byte) 0x80, 0x01, 0x00, 0x00, 0x00, 0x78, 0x00, (byte) rdata.length});
        out.write(rdata);
    }

    private static byte[] getLinkLocalIpv6Bytes() {
        try {
            NetworkInterface wlan = NetworkInterface.getByName("wlan0");
            return wlan != null ? findLinkLocalOnInterface(wlan) : null;
        } catch (Throwable e) { return null; }
    }

    private static byte[] findLinkLocalOnInterface(NetworkInterface wlan) {
        java.util.Enumeration<InetAddress> addrs = wlan.getInetAddresses();
        while (addrs.hasMoreElements()) {
            InetAddress a = addrs.nextElement();
            if (a instanceof java.net.Inet6Address && a.isLinkLocalAddress()) return a.getAddress();
        }
        return null;
    }

    private static void sendPacketSafe(MulticastSocket ms, byte[] payload, InetAddress addr, int port) {
        try {
            if (ms == null || addr == null || payload == null) return;
            InetAddress target = ensureScopedAddress(addr);
            ms.send(new DatagramPacket(payload, payload.length, target, port));
            android.util.Log.i("AuraMdns", "Sent " + payload.length + "B to " + target + ":" + port);
        } catch (Throwable e) {
            android.util.Log.w("AuraMdns", "send err to " + addr + ":" + port + " -> " + e);
        }
    }

    private static InetAddress ensureScopedAddress(InetAddress addr) throws Exception {
        if (addr instanceof java.net.Inet6Address) {
            java.net.Inet6Address v6 = (java.net.Inet6Address) addr;
            if ((v6.isLinkLocalAddress() || v6.isMulticastAddress()) && v6.getScopeId() == 0) {
                NetworkInterface wlan = NetworkInterface.getByName("wlan0");
                if (wlan != null) return java.net.Inet6Address.getByAddress(null, v6.getAddress(), wlan);
            }
        }
        return addr;
    }

    private static InetAddress getInetAddressSafe(String host) {
        try { return InetAddress.getByName(host); } catch (Throwable e) { return null; }
    }

    private static byte[] parseAddressBytes(String ip) {
        try {
            if (ip == null || ip.startsWith("0.")) return null;
            return InetAddress.getByName(ip).getAddress();
        } catch (Throwable e) { return null; }
    }

    private static void registerNsdServiceSafe(Context appCtx, String name, int port) {
        try {
            NsdManager nsd = (NsdManager) appCtx.getSystemService(Context.NSD_SERVICE);
            if (nsd != null) nsd.registerService(buildNsdInfo(name, port), NsdManager.PROTOCOL_DNS_SD, createNoOpNsdListener());
        } catch (Throwable ignored) {}
    }

    private static NsdServiceInfo buildNsdInfo(String name, int port) {
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(name);
        info.setServiceType("_http._tcp.");
        info.setPort(port);
        return info;
    }

    private static NsdManager.RegistrationListener createNoOpNsdListener() {
        return new NsdManager.RegistrationListener() {
            @Override public void onRegistrationFailed(NsdServiceInfo s, int e) {}
            @Override public void onUnregistrationFailed(NsdServiceInfo s, int e) {}
            @Override public void onServiceRegistered(NsdServiceInfo s) {}
            @Override public void onServiceUnregistered(NsdServiceInfo s) {}
        };
    }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
