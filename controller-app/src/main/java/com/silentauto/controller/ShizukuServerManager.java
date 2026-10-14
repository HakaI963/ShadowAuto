package com.silentauto.controller;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.Parcel;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.json.JSONObject;

import rikka.shizuku.Shizuku;

/**
 * Starts and stops the automation shell server bundled in this unified APK
 * via a Shizuku UserService (shell/root identity), replacing the manual
 * {@code adb shell app_process ...} step.
 */
public final class ShizukuServerManager {
    public static final int PORT = 43110;
    public static final int PERMISSION_CODE = 10001;
    public static final int DESTROY_TRANSACTION = 16777115;
    public static final String MANAGER_PACKAGE = "moe.shizuku.privileged.api";
    public static final String MANAGER_DOWNLOAD = "https://shizuku.rikka.app/download/";
    private static final String SERVICE_CLASS = "com.silentauto.shell.ShellUserService";
    private static final String SERVICE_TAG = "shadowauto-shell";
    private static final int SERVICE_VERSION = 6;
    private static final AtomicInteger IDS = new AtomicInteger(1000);

    public interface Callback {
        void onResult(boolean ok, String message);
    }

    public static final class OcrStatus {
        public boolean serverUp;
        public boolean available;
        public String error = "";
        public String diagnosis = "";
    }

    public interface OcrCallback {
        void onStatus(OcrStatus status);
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
        String reason = requestPermissionReason();
        if (reason != null) {
            throw new IllegalStateException(reason);
        }
    }

    /**
     * Tries to send the Shizuku permission request.
     *
     * @return null when the request was handed to Shizuku (watch for the
     * system prompt / manager result); otherwise the concrete reason why no
     * prompt can appear, to show in the UI instead of failing silently.
     */
    public static String requestPermissionReason() {
        try {
            if (!isBinderAlive()) {
                return "Shizuku service is not running";
            }
            if (hasPermission()) {
                return null;
            }
            if (shouldShowRationale()) {
                return "blocked: allowed once with 'don't ask again' - open Shizuku manager and allow this app";
            }
            Shizuku.requestPermission(PERMISSION_CODE);
            return null;
        } catch (Throwable e) {
            String message = e.getMessage();
            return message == null || message.isEmpty()
                    ? e.getClass().getSimpleName() : message;
        }
    }

    public static boolean shouldShowRationale() {
        try {
            return Shizuku.shouldShowRequestPermissionRationale();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isManagerInstalled(Context context) {
        try {
            android.content.pm.PackageManager pm = context.getPackageManager();
            pm.getPackageInfo(MANAGER_PACKAGE, 0);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean openManager(Context context) {
        try {
            android.content.Intent intent =
                    context.getPackageManager().getLaunchIntentForPackage(MANAGER_PACKAGE);
            if (intent == null) {
                return false;
            }
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean openDownloadPage(Context context) {
        try {
            android.content.Intent intent = new android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(MANAGER_DOWNLOAD));
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Point-in-time snapshot of everything the status UI needs. Call off the UI thread. */
    public static final class State {
        public boolean managerInstalled;
        public boolean binderAlive;
        public boolean permissionGranted;
        public boolean permanentlyDenied;
        public boolean serverRunning;
    }

    public static State readState(Context context) {
        State state = new State();
        try {
            state.managerInstalled = isManagerInstalled(context);
        } catch (Throwable ignored) {
            state.managerInstalled = false;
        }
        try {
            state.binderAlive = isBinderAlive();
        } catch (Throwable ignored) {
            state.binderAlive = false;
        }
        try {
            state.permissionGranted = state.binderAlive && hasPermission();
        } catch (Throwable ignored) {
            state.permissionGranted = false;
        }
        try {
            state.permanentlyDenied = !state.permissionGranted && shouldShowRationale();
        } catch (Throwable ignored) {
            state.permanentlyDenied = false;
        }
        try {
            state.serverRunning = isServerRunning();
        } catch (Throwable ignored) {
            state.serverRunning = false;
        }
        return state;
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
            waitForPort(callback, true);
        } catch (Throwable e) {
            callback.onResult(false, "bind failed: " + e.getMessage());
        }
    }

    public static void stopServer(IBinder service, Shizuku.UserServiceArgs args,
                                  ServiceConnection connection, Callback callback) {
        new Thread(() -> {
            try {
                if (service != null) {
                    Parcel data = Parcel.obtain();
                    try {
                        service.transact(DESTROY_TRANSACTION, data, null, 0);
                    } catch (Throwable ignored) {
                    }
                    try {
                        data.recycle();
                    } catch (Throwable ignored) {
                    }
                }
                try {
                    Thread.sleep(800);
                } catch (InterruptedException ignored) {
                    return;
                }
                try {
                    Shizuku.unbindUserService(args, connection, true);
                } catch (Throwable ignored) {
                }
                for (int i = 0; i < 10; i++) {
                    if (!probe(PORT, 300)) {
                        callback.onResult(true, "server stopped");
                        return;
                    }
                    try {
                        Thread.sleep(400);
                    } catch (InterruptedException ignored) {
                        break;
                    }
                }
                callback.onResult(false, "server may still be running");
            } catch (Throwable e) {
                callback.onResult(false, "stop failed: " + e.getMessage());
            }
        }, "shizuku-stop").start();
    }

    public static void unbind(Shizuku.UserServiceArgs args, ServiceConnection connection) {
        try {
            Shizuku.unbindUserService(args, connection, false);
        } catch (Throwable ignored) {
        }
    }

    public static void ensureBoundThenStop(Context context, Shizuku.UserServiceArgs args,
                                           AtomicReference<IBinder> binderOut, Callback callback) {
        try {
            if (!isBinderAlive()) {
                callback.onResult(false, "Shizuku not running");
                return;
            }
            if (!hasPermission()) {
                callback.onResult(false, "Shizuku permission not granted");
                return;
            }
            java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean(false);
            Callback once = (ok, msg) -> {
                if (done.compareAndSet(false, true)) {
                    callback.onResult(ok, msg);
                }
            };
            ServiceConnection conn = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    if (binderOut != null) {
                        binderOut.set(service);
                    }
                    stopServer(service, args, this, once);
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    if (binderOut != null) {
                        binderOut.set(null);
                    }
                }
            };
            Shizuku.bindUserService(args, conn);
            new Thread(() -> {
                try {
                    Thread.sleep(8000);
                } catch (InterruptedException ignored) {
                    return;
                }
                if (binderOut == null || binderOut.get() == null) {
                    once.onResult(false, "could not attach to server; retry");
                }
            }, "shizuku-attach-timeout").start();
        } catch (Throwable e) {
            callback.onResult(false, "stop failed: " + e.getMessage());
        }
    }

    public static void queryOcrStatus(OcrCallback callback) {
        new Thread(() -> {
            OcrStatus status = new OcrStatus();
            status.serverUp = probe(PORT, 800);
            if (!status.serverUp) {
                callback.onStatus(status);
                return;
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", PORT), 2000);
                socket.setSoTimeout(8000);
                BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                int id = IDS.getAndIncrement();
                writer.write("{\"jsonrpc\":\"2.0\",\"id\":" + id
                        + ",\"method\":\"ocrStatus\",\"params\":{}}");
                writer.write('\n');
                writer.flush();
                String line;
                while ((line = reader.readLine()) != null) {
                    try {
                        JSONObject response = new JSONObject(line);
                        if (!response.has("result") || response.optInt("id", -1) != id) {
                            continue;
                        }
                        JSONObject result = response.getJSONObject("result");
                        status.available = result.optBoolean("ocr_available", false);
                        status.error = result.optString("error", "");
                        status.diagnosis = result.optString("diagnosis", "");
                        break;
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable e) {
                status.serverUp = false;
            }
            callback.onStatus(status);
        }, "ocr-status-query").start();
    }

    public static void waitForPort(Callback callback, boolean expectUp) {
        new Thread(() -> {
            for (int i = 0; i < 25; i++) {
                if (probe(PORT, 400) == expectUp) {
                    callback.onResult(true, expectUp ? "server started via Shizuku" : "server stopped");
                    return;
                }
                try {
                    Thread.sleep(400);
                } catch (InterruptedException ignored) {
                    break;
                }
            }
            callback.onResult(false, expectUp
                    ? "server did not open port " + PORT + " in time"
                    : "server may still be running");
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
        return connection(callback, null);
    }

    public static ServiceConnection connection(Callback callback, AtomicReference<IBinder> binderOut) {
        return new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                if (binderOut != null) {
                    binderOut.set(service);
                }
                waitForPort(callback, true);
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                if (binderOut != null) {
                    binderOut.set(null);
                }
                callback.onResult(false, "shell service disconnected");
            }
        };
    }
}
