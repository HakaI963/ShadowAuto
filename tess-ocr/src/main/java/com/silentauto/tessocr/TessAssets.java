package com.silentauto.tessocr;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Deploys the OCR runtime bundled in the APK (assets/ocr/tessdata +
 * native libs) to {@code /data/local/tmp/shadowauto/ocr}, where the shell
 * server expects it. Used both by the controller app process and by the
 * Shizuku shell process (which redeploys from its own assets so files are
 * shell-owned and readable there).
 */
public final class TessAssets {
    public static final String OCR_ROOT = TessOcrEngine.DEFAULT_ROOT;
    private static final String ASSET_BASE = "ocr/tessdata/";

    private static final String[] LEGACY_DIRS = {"models", "labels"};
    private static final String[] LEGACY_LIBS = {
            "libNative.so", "libomp.so",
            "libhiai.so", "libhiai_ir.so", "libhiai_ir_build.so",
    };

    private TessAssets() {
    }

    public static String deploy(Context context) {
        return deploy(context, false);
    }

    public static String deploy(Context context, boolean force) {
        StringBuilder log = new StringBuilder();
        try {
            File root = new File(OCR_ROOT);
            File tessdataDir = new File(root, "tessdata");
            File libDir = new File(root, "lib/arm64-v8a");
            mkdirs(log, tessdataDir);
            mkdirs(log, libDir);

            AssetManager assets = context.getAssets();
            for (String name : TessOcrEngine.LANG_FILES) {
                copyAsset(assets, ASSET_BASE + name, new File(tessdataDir, name), log, force);
            }
            copyNativeLibs(context, libDir, log, force);
            removeLegacy(root, log);
            chmodRecursive(root);
            log.append("ocr deploy done: ").append(TessOcrEngine.diagnose());
        } catch (Throwable e) {
            log.append("ocr deploy failed: ").append(e.getMessage());
        }
        return log.toString();
    }

    private static void mkdirs(StringBuilder log, File dir) {
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            log.append("mkdir failed: ").append(dir).append("; ");
        }
    }

    private static void copyAsset(AssetManager assets, String assetPath, File dest,
                                  StringBuilder log, boolean force) {
        try {
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
        String nativeDir = null;
        try {
            nativeDir = context.getApplicationInfo().nativeLibraryDir;
        } catch (Throwable ignored) {
        }
        if (nativeDir == null) {
            log.append("nativeLibraryDir is null; ");
            return;
        }
        File srcDir = new File(nativeDir);
        for (String name : TessOcrEngine.LIB_LOAD_NAMES) {
            String fileName = System.mapLibraryName(name);
            File src = new File(srcDir, fileName);
            if (!src.isFile()) {
                log.append("lib absent in apk: ").append(fileName).append("; ");
                continue;
            }
            File dest = new File(libDir, fileName);
            try {
                if (!force && dest.isFile() && dest.length() == src.length()) {
                    makeWorldReadable(dest);
                    continue;
                }
                copyFile(src, dest);
                makeWorldReadable(dest);
                log.append("copied lib ").append(fileName).append("; ");
            } catch (Throwable e) {
                log.append("copy lib failed ").append(fileName).append(": ").append(e.getMessage()).append("; ");
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

    private static void removeLegacy(File root, StringBuilder log) {
        try {
            for (String dir : LEGACY_DIRS) {
                deleteRecursive(new File(root, dir));
            }
            File libDir = new File(root, "lib/arm64-v8a");
            for (String name : LEGACY_LIBS) {
                File f = new File(libDir, name);
                if (f.isFile() && !f.delete()) {
                    log.append("cleanup failed: ").append(name).append("; ");
                }
            }
        } catch (Throwable e) {
            log.append("cleanup failed: ").append(e.getMessage()).append("; ");
        }
    }

    private static void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
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
