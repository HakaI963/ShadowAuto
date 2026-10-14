package com.silentauto.controller;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;

import java.net.InetSocketAddress;
import java.net.Socket;

import rikka.shizuku.Shizuku;

/**
 * Starts the automation shell server bundled in this unified APK via a
 * Shizuku UserService (shell/root identity), replacing the manual
 * {@code adb shell app_process ...} step.
 */
public final class ShizukuServerManager {
    public static final int PORT = 43110;
    public static final int PERMISSION_CODE = 10001;
    private static final String SERVICE_CLASS = "com.silentauto.shell.ShellUserService";
    private static final String SERVICE_TAG = "shadowauto-shell";
    private static final int SERVICE_VERSION = 1;

    public interface Callback {
        void onResult(boolean ok, String message);
    }

    private ShizukuServerManager() {
    }

    public static Shizuku.UserServiceArgs serviceArgs(Context context) {
        return new Shizuku.UserServiceArgs(
                new ComponentName(context.getPackageName(), SERVICE_CLASS))
                .processNameSuffix("shell")
                .tag(SERVICE_TAG)
                .version(SERVICE_VERSION)
                .daemon(true)
                .debuggable(false);
    }

    public static boolean isBinderAlive() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean hasPermission() {
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void requestPermission() {
        try {
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                return;
            }
            Shizuku.requestPermission(PERMISSION_CODE);
        } catch (Throwable ignored) {
        }
    }

    public static boolean isServerRunning() {
        return probe(PORT, 700);
    }

    public static void startServer(Context context, Shizuku.UserServiceArgs args,
                                   ServiceConnection connection, Callback callback) {
        try {
            if (!isBinderAlive()) {
                callback.onResult(false, "Shizuku not running: start Shizuku first, then retry");
                return;
            }
            if (!hasPermission()) {
                callback.onResult(false, "Shizuku permission not granted: grant it, then retry");
                return;
            }
            Shizuku.bindUserService(args, connection);
            waitForPort(callback);
        } catch (Throwable e) {
            callback.onResult(false, "bind failed: " + e.getMessage());
        }
    }

    public static void unbind(Shizuku.UserServiceArgs args, ServiceConnection connection) {
        try {
            Shizuku.unbindUserService(args, connection, false);
        } catch (Throwable ignored) {
        }
    }

    private static void waitForPort(Callback callback) {
        new Thread(() -> {
            for (int i = 0; i < 25; i++) {
                if (probe(PORT, 400)) {
                    callback.onResult(true, "server started via Shizuku");
                    return;
                }
                try {
                    Thread.sleep(400);
                } catch (InterruptedException ignored) {
                    break;
                }
            }
            callback.onResult(false, "server did not open port " + PORT + " in time");
        }, "shizuku-probe").start();
    }

    private static boolean probe(int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), timeoutMs);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static ServiceConnection connection(Callback callback) {
        return new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                waitForPort(callback);
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                callback.onResult(false, "shell service disconnected");
            }
        };
    }
}
