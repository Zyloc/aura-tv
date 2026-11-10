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
 * Keeps TV Wi-Fi in high-performance low-latency mode and responds to mDNS (.local)
 * queries for mitv.local, aura.local, and auratv.local directly on the TV.
 */
public final class LocalDomainResponder {
    private static final String MCAST_IP = "224.0.0.251";
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
        String ip = QrRemoteBuilder.getTvIpAddress(ctx);
        byte[] ipBytes = parseIpv4Bytes(ip);
        if (ipBytes == null || sSocket == null || sSocket.isClosed()) return;
        for (String alias : HOST_ALIASES) {
            sendMdnsAnnouncement(alias, ipBytes);
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
            InetAddress group = InetAddress.getByName(MCAST_IP);
            NetworkInterface wlan = NetworkInterface.getByName("wlan0");
            if (wlan != null) ms.setNetworkInterface(wlan);
            ms.joinGroup(group);
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
        if (len < 12 || (data[2] & 0x80) != 0) return;
        String matchedAlias = findMatchingHostAlias(data, len);
        if (matchedAlias != null) replyToMdnsQuery(appCtx, ms, pkt, matchedAlias);
    }

    private static String findMatchingHostAlias(byte[] data, int len) {
        String lower = new String(data, 12, len - 12, StandardCharsets.ISO_8859_1).toLowerCase();
        for (String alias : HOST_ALIASES) {
            String pat = ((char) alias.length()) + alias + "\u0005local";
            if (lower.contains(pat)) return alias;
        }
        return null;
    }

    private static void replyToMdnsQuery(Context appCtx, MulticastSocket ms, DatagramPacket query, String alias) {
        byte[] ipBytes = parseIpv4Bytes(QrRemoteBuilder.getTvIpAddress(appCtx));
        if (ipBytes == null) return;
        byte[] qData = query.getData();
        sendReplyPacket(ms, buildDnsAnswer(qData[0], qData[1], alias, ipBytes), query.getAddress(), query.getPort());
        sendReplyPacket(ms, buildDnsAnswer((byte) 0, (byte) 0, alias, ipBytes), getMcastAddressSafe(), MDNS_PORT);
    }

    private static void sendMdnsAnnouncement(String alias, byte[] ipBytes) {
        byte[] payload = buildDnsAnswer((byte) 0, (byte) 0, alias, ipBytes);
        sendReplyPacket(sSocket, payload, getInetAddressSafe(MCAST_IP), MDNS_PORT);
        for (String peer : QrRemoteBuilder.getLiveArpPeers()) {
            sendReplyPacket(sSocket, payload, getInetAddressSafe(peer), MDNS_PORT);
        }
    }

    private static void sendReplyPacket(MulticastSocket ms, byte[] payload, InetAddress addr, int port) {
        try {
            if (ms != null && addr != null && payload != null) {
                ms.send(new DatagramPacket(payload, payload.length, addr, port));
            }
        } catch (Throwable ignored) {}
    }

    private static InetAddress getMcastAddressSafe() {
        return getInetAddressSafe(MCAST_IP);
    }

    private static InetAddress getInetAddressSafe(String host) {
        try { return InetAddress.getByName(host); } catch (Throwable e) { return null; }
    }

    private static byte[] buildDnsAnswer(byte txHi, byte txLo, String alias, byte[] ipBytes) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(96);
            writeDnsHeader(out, txHi, txLo);
            writeDomainName(out, alias);
            writeARecordTail(out, ipBytes);
            writeNsecRecord(out);
            return out.toByteArray();
        } catch (Throwable e) { return null; }
    }

    private static void writeDnsHeader(ByteArrayOutputStream out, byte txHi, byte txLo) {
        byte[] hdr = {txHi, txLo, (byte) 0x84, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01};
        out.write(hdr, 0, hdr.length);
    }

    private static void writeDomainName(ByteArrayOutputStream out, String alias) throws Exception {
        byte[] label = alias.getBytes(StandardCharsets.US_ASCII);
        out.write(label.length);
        out.write(label);
        byte[] localTail = {0x05, 'l', 'o', 'c', 'a', 'l', 0x00};
        out.write(localTail);
    }

    private static void writeARecordTail(ByteArrayOutputStream out, byte[] ipBytes) throws Exception {
        byte[] meta = {0x00, 0x01, (byte) 0x80, 0x01, 0x00, 0x00, 0x00, 0x78, 0x00, 0x04};
        out.write(meta);
        out.write(ipBytes);
    }

    private static void writeNsecRecord(ByteArrayOutputStream out) throws Exception {
        byte[] nsec = {(byte) 0xC0, 0x0C, 0x00, 0x2F, (byte) 0x80, 0x01,
            0x00, 0x00, 0x00, 0x78, 0x00, 0x05, (byte) 0xC0, 0x0C, 0x00, 0x01, 0x40};
        out.write(nsec);
    }

    private static byte[] parseIpv4Bytes(String ip) {
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
