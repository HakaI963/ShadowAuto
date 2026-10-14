package com.silentauto.shell;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Binder;
import android.os.Looper;
import android.os.Parcel;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

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
    private static final String OCR_ROOT = "/data/local/tmp/shadowauto/ocr";
    private static volatile boolean started;

    private static final String[] OCR_ASSETS = {
            "ocr/models/ch_PP-OCRv2/det_db.nb",
            "ocr/models/ch_PP-OCRv2/rec_crnn.nb",
            "ocr/models/ch_PP-OCRv2/cls.nb",
            "ocr/labels/ppocr_keys_v1.txt",
    };
    private static final String[] NATIVE_LIBS = {
            "libNative.so",
            "libc++_shared.so",
            "libomp.so",
            "libhiai.so",
            "libhiai_ir.so",
            "libhiai_ir_build.so",
    };

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
                Log.i(TAG, deployOcr(context));
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

    private static String deployOcr(Context context) {
        StringBuilder log = new StringBuilder("ocr deploy (shell): ");
        File root = new File(OCR_ROOT);
        File modelDir = new File(root, "models/ch_PP-OCRv2");
        File labelDir = new File(root, "labels");
        File libDir = new File(root, "lib/arm64-v8a");
        modelDir.mkdirs();
        labelDir.mkdirs();
        libDir.mkdirs();
        AssetManager assets = context.getAssets();
        for (String assetPath : OCR_ASSETS) {
            File dest = new File(root, assetPath.substring("ocr/".length()));
            try {
                File parent = dest.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                try (InputStream in = assets.open(assetPath);
                     OutputStream out = new FileOutputStream(dest)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                    }
                }
                dest.setReadable(true, false);
                log.append("ok:").append(assetPath).append(' ');
            } catch (Throwable e) {
                log.append("fail:").append(assetPath).append('=').append(e.getMessage()).append(' ');
            }
        }
        String nativeDir = null;
        try {
            nativeDir = context.getApplicationInfo().nativeLibraryDir;
        } catch (Throwable ignored) {
        }
        if (nativeDir != null) {
            for (String name : NATIVE_LIBS) {
                File src = new File(nativeDir, name);
                if (!src.isFile()) {
                    continue;
                }
                try {
                    copyFile(src, new File(libDir, name));
                    new File(libDir, name).setReadable(true, false);
                    log.append("ok:lib/").append(name).append(' ');
                } catch (Throwable e) {
                    log.append("fail:lib/").append(name).append(' ');
                }
            }
        } else {
            log.append("nativeLibraryDir=null ");
        }
        try {
            Runtime.getRuntime().exec(new String[]{"chmod", "-R", "755", OCR_ROOT}).waitFor();
        } catch (Throwable ignored) {
        }
        log.append(diagnose());
        return log.toString();
    }

    private static String diagnose() {
        File root = new File(OCR_ROOT);
        StringBuilder missing = new StringBuilder();
        String[] expected = {
                "models/ch_PP-OCRv2/det_db.nb",
                "models/ch_PP-OCRv2/rec_crnn.nb",
                "models/ch_PP-OCRv2/cls.nb",
                "labels/ppocr_keys_v1.txt",
                "lib/arm64-v8a/libNative.so",
                "lib/arm64-v8a/libc++_shared.so",
                "lib/arm64-v8a/libomp.so",
        };
        for (String rel : expected) {
            if (!new File(root, rel).isFile()) {
                if (missing.length() > 0) {
                    missing.append(',');
                }
                missing.append(rel);
            }
        }
        return missing.length() == 0 ? "all ocr files present" : ("missing: " + missing);
    }

    private static void copyFile(File src, File dest) throws Exception {
        try (InputStream in = new java.io.FileInputStream(src);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
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
