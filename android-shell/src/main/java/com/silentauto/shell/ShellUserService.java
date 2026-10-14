package com.silentauto.shell;

import android.content.Context;
import android.os.Binder;
import android.os.Looper;
import android.os.Parcel;

/**
 * Shizuku UserService entry point for the unified APK.
 *
 * <p>Runs with shell (or root) identity in a dedicated process started by
 * Shizuku, hosting the same automation stack as {@link Main} (VirtualDisplay,
 * UiAutomation, input injection, OCR, JSON-RPC on 127.0.0.1). The controller
 * UI binds this service via {@code Shizuku.bindUserService} instead of the
 * legacy manual {@code adb shell app_process ...} step.
 *
 * <p>Transaction code 16777115 is the conventional "destroy" call: it stops
 * all tasks and exits the service process.
 */
public class ShellUserService extends Binder {
    private static final int TRANSACTION_DESTROY = 16777115;
    private static volatile boolean started;

    public ShellUserService() {
        start();
    }

    public ShellUserService(Context context) {
        start();
    }

    private static synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        Thread thread = new Thread(() -> {
            try {
                Main.main(new String[]{"--port=43110"});
            } catch (Throwable ignored) {
            }
        }, "shell-userservice-main");
        thread.setDaemon(false);
        thread.start();
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws android.os.RemoteException {
        if (code == TRANSACTION_DESTROY) {
            try {
                data.enforceInterface(getInterfaceDescriptor());
            } catch (Throwable ignored) {
            }
            new Thread(() -> {
                try {
                    Thread.sleep(300);
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
