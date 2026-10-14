package com.silentauto.controller;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Deploys the OCR runtime bundled in this unified APK (assets/ocr + native
 * libs) to /data/local/tmp/shadowauto/ocr, where the shell server
 * (com.silentauto.shell.Main, started via Shizuku) expects it.
 *
 * Any app on the device can read/write /data/local/tmp, so plain File I/O
 * from the app process works; Shizuku is only needed to start the server,
 * not for the copy itself.
 */
public final class OcrAssetDeployer {
    public static final String OCR_ROOT = "/data/local/tmp/shadowauto/ocr";

    private static final String[] MODEL_FILES = {
            "ocr/models/ch_PP-OCRv2/det_db.nb",
            "ocr/models/ch_PP-OCRv2/rec_crnn.nb",
            "ocr/models/ch_PP-OCRv2/cls.nb",
    };
    private static final String[] LABEL_FILES = {
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

    private OcrAssetDeployer() {
    }

    public static String deploy(Context context) {
        return deploy(context, false);
    }

    public static String deploy(Context context, boolean force) {
        StringBuilder log = new StringBuilder();
        try {
            File root = new File(OCR_ROOT);
            File modelDir = new File(root, "models/ch_PP-OCRv2");
            File labelDir = new File(root, "labels");
            File libDir = new File(root, "lib/arm64-v8a");
            mkdirs(log, modelDir);
            mkdirs(log, labelDir);
            mkdirs(log, libDir);

            AssetManager assets = context.getAssets();
            for (String assetPath : MODEL_FILES) {
                copyAsset(assets, assetPath, new File(root, assetPath.substring("ocr/".length())), log, force);
            }
            for (String assetPath : LABEL_FILES) {
                copyAsset(assets, assetPath, new File(root, assetPath.substring("ocr/".length())), log, force);
            }
            copyNativeLibs(context, libDir, log, force);
            chmodRecursive(root);
            log.append("ocr deploy done: ").append(diagnose());
        } catch (Throwable e) {
            log.append("ocr deploy failed: ").append(e.getMessage());
        }
        return log.toString();
    }

    public static String diagnose() {
        File root = new File(OCR_ROOT);
        if (!root.isDirectory()) {
            return "ocr root missing: " + root;
        }
        StringBuilder missing = new StringBuilder();
        check(missing, new File(root, "models/ch_PP-OCRv2/det_db.nb"));
        check(missing, new File(root, "models/ch_PP-OCRv2/rec_crnn.nb"));
        check(missing, new File(root, "models/ch_PP-OCRv2/cls.nb"));
        check(missing, new File(root, "labels/ppocr_keys_v1.txt"));
        check(missing, new File(root, "lib/arm64-v8a/libNative.so"));
        check(missing, new File(root, "lib/arm64-v8a/libc++_shared.so"));
        check(missing, new File(root, "lib/arm64-v8a/libomp.so"));
        if (missing.length() == 0) {
            return "all expected OCR files present under " + root;
        }
        return "missing under " + root + ": " + missing;
    }

    private static void check(StringBuilder missing, File file) {
        if (!file.isFile()) {
            if (missing.length() > 0) {
                missing.append(',');
            }
            missing.append(file.getAbsolutePath().substring(OCR_ROOT.length() + 1));
        }
    }

    private static void mkdirs(StringBuilder log, File dir) {
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            log.append("mkdir failed: ").append(dir).append("; ");
        }
    }

    private static void copyAsset(AssetManager assets, String assetPath, File dest, StringBuilder log, boolean force) {
        try {
            // Note: AssetInputStream.available() is unreliable for compressed
            // assets, so only skip when not forcing and a non-empty file exists.
            // Shell-side redeploy (ShellUserService) always overwrites.
            if (!force && dest.isFile() && dest.length() > 0) {
                makeWorldReadable(dest);
                return;
            }
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
            makeWorldReadable(dest);
            log.append("copied ").append(assetPath).append("; ");
        } catch (Throwable e) {
            log.append("copy failed ").append(assetPath).append(": ").append(e.getMessage()).append("; ");
        }
    }

    private static void copyNativeLibs(Context context, File libDir, StringBuilder log, boolean force) {
        String nativeDir = context.getApplicationInfo().nativeLibraryDir;
        if (nativeDir == null) {
            log.append("nativeLibraryDir is null; ");
            return;
        }
        File srcDir = new File(nativeDir);
        for (String name : NATIVE_LIBS) {
            File src = new File(srcDir, name);
            if (!src.isFile()) {
                continue;
            }
            File dest = new File(libDir, name);
            try {
                if (!force && dest.isFile() && dest.length() == src.length()) {
                    makeWorldReadable(dest);
                    continue;
                }
                copyFile(src, dest);
                makeWorldReadable(dest);
                log.append("copied lib ").append(name).append("; ");
            } catch (Throwable e) {
                log.append("copy lib failed ").append(name).append(": ").append(e.getMessage()).append("; ");
            }
        }
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

    private static void makeWorldReadable(File file) {
        try {
            file.setReadable(true, false);
        } catch (Throwable ignored) {
        }
    }

    private static void chmodRecursive(File root) {
        try {
            Runtime.getRuntime().exec(new String[]{"chmod", "-R", "755", root.getAbsolutePath()}).waitFor();
        } catch (Throwable ignored) {
        }
    }
}
