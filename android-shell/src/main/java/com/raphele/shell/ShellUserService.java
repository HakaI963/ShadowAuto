package com.raphele.shell;

import android.content.Context;
import android.os.Binder;
import android.os.Looper;
import android.os.Parcel;
import android.util.Log;

import com.raphele.tessocr.TessAssets;

/**
 * Shizuku UserService entry point for the unified APK.
 *
 * <p>Runs with shell (or root) identity in a dedicated process started by
 * Shizuku, hosting the same automation stack as {@link Main} (VirtualDisplay,
 * UiAutomation, input injection, OCR, JSON-RPC on 127.0.0.1). The controller
 * UI binds this service via {@code Shizuku.bindUserService} instead of the
 * legacy manual {@code adb shell app_process ...} step.
 *
 * <p>OCR files deployed by the app process can be unreadable from this
 * process (different UID/SELinux label), so this service redeploys the OCR
 * runtime from its own APK assets before starting {@link Main}. Files created
 * here are owned by shell and readable by this process.
 *
 * <p>Transaction code 16777115 is the conventional "destroy" call: it stops
 * all tasks and exits the service process.
 */
public class ShellUserService extends Binder {
    private static final String TAG = "ShellUserService";
    private static final int TRANSACTION_DESTROY = 16777115;
    /**
     * Guards {@link #startupComplete}. A DESTROY request can arrive right
     * after the app binds (much faster than {@link Main} finishes reclaiming
     * the RPC port), so the destroying thread waits here until startup has
     * killed leftover port holders. Otherwise a stale daemon survives the
     * stop request and keeps answering on the port.
     */
    private static final Object STARTUP_LOCK = new Object();
    private static volatile boolean started;
    private static boolean startupComplete;

    public ShellUserService() {
        start(null);
    }

    public ShellUserService(Context context) {
        start(context);
    }

    private static synchronized void start(Context context) {
        if (started) {
            return;
        }
        started = true;
        if (context != null) {
            try {
                String nativeDir = context.getApplicationInfo().nativeLibraryDir;
                com.raphele.tessocr.TessOcrEngine.nativeLibDirOverride = nativeDir;
                android.util.Log.i(TAG, "native lib dir: " + nativeDir);
            } catch (Throwable e) {
                android.util.Log.w(TAG, "no native lib dir: " + e.getMessage());
            }
            try {
                // Redeploy as shell so OCR files are shell-owned and readable
                // in this process (app-deployed copies may carry the wrong
                // UID/SELinux label).
                Log.i(TAG, TessAssets.deploy(context));
            } catch (Throwable e) {
                Log.w(TAG, "ocr deploy failed: " + e.getMessage());
            }
        } else {
            Log.w(TAG, "no context, skipping shell-side ocr deploy");
        }
        Thread thread = new Thread(() -> {
            try {
                Main.main(new String[]{"--port=43110"});
            } catch (Throwable ignored) {
            }
        }, "shell-userservice-main");
        thread.setDaemon(false);
        thread.start();
    }

    /**
     * Called by {@link Main} once the RPC port has been reclaimed (stale
     * port holders killed). A pending DESTROY may then exit this process
     * without leaving an old server behind on the port.
     */
    public static void notifyStartupComplete() {
        synchronized (STARTUP_LOCK) {
            startupComplete = true;
            STARTUP_LOCK.notifyAll();
        }
    }

    private static boolean startupWait(long timeoutMs) {
        synchronized (STARTUP_LOCK) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (!startupComplete) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return false;
                }
                try {
                    STARTUP_LOCK.wait(Math.min(250, remaining));
                } catch (InterruptedException e) {
                    return startupComplete;
                }
            }
            return true;
        }
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws android.os.RemoteException {
        if (code == TRANSACTION_DESTROY) {
            try {
                data.enforceInterface(getInterfaceDescriptor());
            } catch (Throwable ignored) {
            }
            Main.shutdown();
            new Thread(() -> {
                // Let Main reclaim the port first (kills leftover daemons),
                // bounded so a stuck startup can never hang the destroy.
                startupWait(15000);
                try {
                    Thread.sleep(250);
                } catch (InterruptedException ignored) {
                }
                try {
                    if (Looper.myLooper() != null) {
                        Looper.myLooper().quit();
                    }
                } catch (Throwable ignored) {
                }
                System.exit(0);
            }, "shell-userservice-destroy").start();
            return true;
        }
        return super.onTransact(code, data, reply, flags);
    }
}
