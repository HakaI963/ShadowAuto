package com.silentauto.tessocr;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Deploys the OCR runtime bundled in the APK (assets/ocr/tessdata +
 * native libs) to {@code /data/local/tmp/shadowauto/ocr}, where the shell
 * server expects it. Used both by the controller app process and by the
 * Shizuku shell process (which redeploys from its own assets so files are
 * shell-owned and readable there).
 *
 * <p>Native libs are taken from {@code nativeLibraryDir} when the OS
 * extracted them, otherwise unpacked straight from our own APK file, so the
 * deploy works regardless of the extractNativeLibs packaging mode. Every
 * file and directory under the OCR root is then made world-readable (dirs
 * also traversable) in pure Java, no shell required.
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
            mkdirs(log, root);
            mkdirs(log, tessdataDir);
            mkdirs(log, libDir);

            AssetManager assets = context.getAssets();
            for (String name : TessOcrEngine.LANG_FILES) {
                copyAsset(assets, ASSET_BASE + name, new File(tessdataDir, name), log, force);
            }
            copyNativeLibs(context, libDir, log, force);
            removeLegacy(root, log);
            fixPermissions(root, log);
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
            return;
        }
        accessibleDir(dir);
    }

    private static void copyAsset(AssetManager assets, String assetPath, File dest,
                                  StringBuilder log, boolean force) {
        try {
            // traineddata files are tens of MB; anything smaller is a
            // truncated copy from an interrupted deploy, so redo it.
            if (!force && dest.isFile() && dest.length() > 1000000) {
                accessibleFile(dest, false);
                return;
            }
            File parent = dest.getParentFile();
            if (parent != null) {
                parent.mkdirs();
                accessibleDir(parent);
            }
            try (InputStream in = assets.open(assetPath);
                 OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            }
            accessibleFile(dest, false);
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
        for (String name : TessOcrEngine.LIB_LOAD_NAMES) {
            String fileName = System.mapLibraryName(name);
            File dest = new File(libDir, fileName);
            try {
                File src = nativeDir == null ? null : new File(nativeDir, fileName);
                boolean haveSrc = src != null && src.isFile();
                if (!haveSrc) {
                    haveSrc = unpackFromApk(context, fileName, dest, log, force);
                    if (haveSrc) {
                        continue;
                    }
                    log.append("lib unavailable: ").append(fileName).append("; ");
                    continue;
                }
                if (!force && dest.isFile() && dest.length() == src.length()) {
                    accessibleFile(dest, true);
                    continue;
                }
                copyFile(src, dest);
                accessibleFile(dest, true);
                log.append("copied lib ").append(fileName).append("; ");
            } catch (Throwable e) {
                log.append("copy lib failed ").append(fileName).append(": ").append(e.getMessage()).append("; ");
            }
        }
    }

    /**
     * Falls back to reading the .so straight from our own APK file. Covers
     * devices/ROMs where the OS did not extract native libs to
     * nativeLibraryDir (unextracted-APK packaging mode).
     *
     * @return true when the destination is now a valid copy.
     */
    private static boolean unpackFromApk(Context context, String fileName, File dest,
                                         StringBuilder log, boolean force) {
        if (!force && dest.isFile() && dest.length() > 100000) {
            accessibleFile(dest, true);
            return true;
        }
        String apkPath = null;
        try {
            apkPath = context.getApplicationInfo().sourceDir;
        } catch (Throwable ignored) {
        }
        if (apkPath == null) {
            return false;
        }
        ZipFile apk = null;
        try {
            apk = new ZipFile(apkPath);
            ZipEntry entry = apk.getEntry("lib/arm64-v8a/" + fileName);
            if (entry == null) {
                java.util.Enumeration<? extends ZipEntry> entries = apk.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry candidate = entries.nextElement();
                    String entryName = candidate.getName();
                    if (entryName.endsWith("/" + fileName)) {
                        entry = candidate;
                        break;
                    }
                }
            }
            if (entry == null) {
                log.append("lib not in apk: ").append(fileName).append("; ");
                return false;
            }
            File parent = dest.getParentFile();
            if (parent != null) {
                parent.mkdirs();
                accessibleDir(parent);
            }
            try (InputStream in = apk.getInputStream(entry);
                 OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            }
            accessibleFile(dest, true);
            log.append("unpacked lib from apk: ").append(fileName).append("; ");
            return dest.isFile() && dest.length() > 100000;
        } catch (Throwable e) {
            log.append("unpack failed ").append(fileName).append(": ").append(e.getMessage()).append("; ");
            return false;
        } finally {
            if (apk != null) {
                try {
                    apk.close();
                } catch (Throwable ignored) {
                }
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

    /**
     * Makes everything under the OCR root world-readable (directories also
     * traversable) using only Java APIs, so the shell-UID server process can
     * read them even when the chmod binary is unavailable. Never touches
     * anything above the OCR root.
     */
    private static void fixPermissions(File root, StringBuilder log) {
        try {
            fixRecursive(root);
        } catch (Throwable e) {
            log.append("perm fix failed: ").append(e.getMessage()).append("; ");
        }
    }

    private static void fixRecursive(File file) {
        if (file.isDirectory()) {
            accessibleDir(file);
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    fixRecursive(child);
                }
            }
        } else if (file.isFile()) {
            accessibleFile(file, file.getName().endsWith(".so"));
        }
    }

    private static void accessibleDir(File dir) {
        try {
            dir.setReadable(true, false);
            dir.setExecutable(true, false);
        } catch (Throwable ignored) {
        }
    }

    private static void accessibleFile(File file, boolean executable) {
        try {
            file.setReadable(true, false);
            if (executable) {
                file.setExecutable(true, false);
            }
        } catch (Throwable ignored) {
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

    private static void chmodRecursive(File root) {
        try {
            Runtime.getRuntime().exec(new String[]{"chmod", "-R", "755", root.getAbsolutePath()}).waitFor();
        } catch (Throwable ignored) {
        }
    }
}
