package com.shilapi.xcertplay.compat;

import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.util.Log;
import java.io.BufferedReader;
import java.io.StringReader;
import java.lang.reflect.Method;
import java.net.Socket;

/**
 * H52 GKUI No-Root execution bridge and performance optimization helper.
 * Leverages the firmware-native Neusoft 'usbprotect' service (uid=1000)
 * via transaction 0x16 to bypass client-side whitelist and execute system shell.
 */
public final class H52NoRootBridge {
    private static final String TAG = "H52NoRootBridge";
    private static final String SERVICE_NAME = "usbprotect";
    private static final String INTERFACE_TOKEN = "com.neusoft.c3alfus.projectservice.IUsbProtectService";
    private static final int TRANSACTION_SHELL = 0x16;

    /**
     * Executes a shell command via the firmware usbprotect service (uid=1000).
     */
    public static String execSystem(String cmd) {
        if (cmd == null || cmd.length() == 0) return "";
        try {
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Method getService = smClass.getMethod("getService", String.class);
            IBinder binder = (IBinder) getService.invoke(null, SERVICE_NAME);
            if (binder == null) {
                Log.w(TAG, "usbprotect service not found in ServiceManager");
                return null;
            }
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(INTERFACE_TOKEN);
                data.writeByteArray(cmd.getBytes("UTF-8"));
                binder.transact(TRANSACTION_SHELL, data, reply, 0);
                reply.readException();
                byte[] resp = reply.createByteArray();
                String out = resp != null ? new String(resp, "UTF-8") : "";
                Log.i(TAG, "execSystem cmd='" + cmd + "' result len=" + out.length());
                return out;
            } finally {
                data.recycle();
                reply.recycle();
            }
        } catch (Throwable t) {
            Log.w(TAG, "execSystem failed via usbprotect: " + t.getMessage());
            return null;
        }
    }

    /**
     * Reads active hostapd channel without requiring su.
     * Fallbacks to 36 for 5GHz if unreadable or configured=0.
     */
    public static int readHostapdChannel(int observed, int configured) {
        if (observed > 0 && observed <= 196) return observed;

        String out = execSystem("cat /data/misc/wifi/hostapd.conf");
        if (out != null && out.length() > 0) {
            try {
                BufferedReader r = new BufferedReader(new StringReader(out));
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("channel=")) {
                        int c = Integer.parseInt(line.substring(8).trim());
                        if (c > 0 && c <= 196) {
                            Log.i(TAG, "active hostapd channel=" + c + " via usbprotect");
                            return c;
                        }
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "parse hostapd.conf failed", t);
            }
        }

        // Fallback: If configured is valid, use it; otherwise, default to 36 (Geely H52 5GHz default)
        if (configured > 0 && configured <= 196) {
            return configured;
        }
        Log.i(TAG, "defaulting to channel 36 for H52 5GHz AP");
        return 36;
    }

    /**
     * Switches USB configuration to target (e.g. 6) via usbprotect without su.
     */
    public static boolean switchUsbConfiguration(int target, String sysfsPath) {
        Log.i(TAG, "switchUsbConfiguration target=" + target + " path=" + sysfsPath);
        String cmd = "echo " + target + " > " + sysfsPath;
        String res = execSystem(cmd);
        if (res != null) {
            Log.i(TAG, "switchUsbConfiguration succeeded via usbprotect");
            return true;
        }
        return false;
    }

    /**
     * Boosts current thread priority to URGENT_DISPLAY (Nice -8) for video pipelines.
     */
    public static void boostThreadPriority() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
            Log.i(TAG, "thread " + Thread.currentThread().getName() + " boosted to URGENT_DISPLAY");
        } catch (Throwable t) {
            Log.w(TAG, "boostThreadPriority failed: " + t.getMessage());
        }
    }

    /**
     * Configures socket for zero-delay video streaming.
     */
    public static void tuneVideoSocket(Socket s) {
        if (s == null) return;
        try {
            s.setTcpNoDelay(true);
            s.setReceiveBufferSize(524288); // 512 KB
            Log.i(TAG, "video socket tuned: tcpNoDelay=true rcvBuf=" + s.getReceiveBufferSize());
        } catch (Throwable t) {
            Log.w(TAG, "tuneVideoSocket failed: " + t.getMessage());
        }
    }
}
