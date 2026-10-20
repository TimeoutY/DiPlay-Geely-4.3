package com.shilapi.xcertplay.compat;
import android.util.Log;
import java.net.*;
import java.util.concurrent.atomic.*;

public final class H52WirelessDiagnostics {
    private static final AtomicInteger rx = new AtomicInteger(), tx = new AtomicInteger();

    public static int channel(int observed, int configured) {
        if (observed > 0) return observed;
        int c = H52NoRootBridge.readHostapdChannel();
        if (c > 0) {
            Log.i("DiPlay-H52-Wireless", "active AP channel=" + c + " source=hostapd/default36");
            return c;
        }
        return configured;
    }

    public static void packet(DatagramPacket p, boolean incoming) {
        int n = (incoming ? rx : tx).incrementAndGet();
        if (n > 12) return;
        byte[] b = p.getData();
        int o = p.getOffset(), len = p.getLength();
        int flags = len >= 4 ? ((b[o + 2] & 255) << 8) | (b[o + 3] & 255) : 0;
        Log.i("DiPlay-H52-mDNS", (incoming ? "RX" : "TX") + " n=" + n + " bytes=" + len + " response=" + ((flags & 32768) != 0) + " multicast=" + p.getAddress().isMulticastAddress() + " family=" + (p.getAddress() instanceof Inet4Address ? "IPv4" : "IPv6"));
    }
}
