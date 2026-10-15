package com.raphele.tessocr;

import android.graphics.Bitmap;
import android.graphics.Rect;

import com.googlecode.tesseract.android.ResultIterator;
import com.googlecode.tesseract.android.TessBaseAPI;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Offline OCR via Tesseract (tess-two, prebuilt native libs from Maven).
 *
 * <p>No NDK/CMake build, no Play Services, no model server: the engine reads
 * {@code tessdata/*.traineddata} and native libs from {@link #DEFAULT_ROOT},
 * which the unified app deploys from its own assets on launch. Runs in any
 * process (app or Shizuku shell) with plain file I/O.
 */
public final class TessOcrEngine implements AutoCloseable {
    public static final String DEFAULT_ROOT = "/data/local/tmp/raphele/ocr";
    public static final String DEFAULT_TESSDATA_DIR = DEFAULT_ROOT + "/tessdata";
    public static final String DEFAULT_LIB_DIR = DEFAULT_ROOT + "/lib/arm64-v8a";
    public static final String DEFAULT_LANG = "chi_sim+eng";

    /** Screenshots are downscaled to this long side before recognition. */
    public static final int MAX_SIDE = 800;

    /** Dependency order: C++ runtime, image codecs, leptonica, tesseract. */
    static final String[] LIB_LOAD_NAMES = {"c++_shared", "jpeg", "pngx", "leptonica", "tesseract"};
    static final String[] LANG_FILES = {"chi_sim.traineddata", "eng.traineddata"};

    /**
     * Set by whoever starts the shell process with a Context (ShellUserService)
     * so the engine can also try the app's extracted native libs, which carry
     * a friendlier SELinux label than /data/local/tmp copies.
     */
    public static volatile String nativeLibDirOverride;

    private TessBaseAPI api;
    private int pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO;

    public static boolean isNativeAvailable() {
        try {
            loadLibraries(new File(DEFAULT_LIB_DIR), nativeLibDir());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static File nativeLibDir() {
        try {
            String path = nativeLibDirOverride;
            if (path != null && !path.isEmpty()) {
                return new File(path);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static String diagnose() {
        File root = new File(DEFAULT_ROOT);
        if (!root.isDirectory()) {
            return "ocr root missing: " + root + " (nothing deployed yet)";
        }
        StringBuilder missing = new StringBuilder();
        StringBuilder present = new StringBuilder();
        for (String name : LANG_FILES) {
            stat(root, "tessdata/" + name, missing, present);
        }
        for (String name : LIB_LOAD_NAMES) {
            stat(root, "lib/arm64-v8a/" + System.mapLibraryName(name), missing, present);
        }
        if (missing.length() == 0) {
            return "all expected OCR files present under " + root + ": " + present;
        }
        return "missing under " + root + ": " + missing + "; present: " + present;
    }

    private static void stat(File root, String rel, StringBuilder missing, StringBuilder present) {
        File file = new File(root, rel);
        if (file.isFile()) {
            if (present.length() > 0) {
                present.append(',');
            }
            present.append(rel).append('(').append(file.length()).append(')');
        } else {
            if (missing.length() > 0) {
                missing.append(',');
            }
            missing.append(rel);
        }
    }

    /**
     * Makes the native libs loadable in this process. For each lib, in
     * dependency order, tries {@code System.loadLibrary} (works when the host
     * sets up the app native path), then the app's extracted native libs
     * (friendliest SELinux label), then the absolute copies under
     * {@code libDir} (/data/local/tmp flows). The first error aborts with the
     * file name and size attached.
     */
    static void loadLibraries(File libDir, File appLibDir) {
        for (String name : LIB_LOAD_NAMES) {
            String fileName = System.mapLibraryName(name);
            boolean loaded = false;
            Throwable firstError = null;
            try {
                System.loadLibrary(name);
                loaded = true;
            } catch (Throwable e) {
                firstError = e;
            }
            if (!loaded && appLibDir != null) {
                File file = new File(appLibDir, fileName);
                if (file.isFile()) {
                    try {
                        System.load(file.getAbsolutePath());
                        loaded = true;
                    } catch (Throwable e) {
                        firstError = e;
                    }
                }
            }
            if (!loaded) {
                File file = new File(libDir, fileName);
                if (!file.isFile()) {
                    throw new IllegalStateException("Missing native lib: " + file
                            + " (loadLibrary failed: " + message(firstError) + ")");
                }
                try {
                    System.load(file.getAbsolutePath());
                    loaded = true;
                } catch (Throwable e) {
                    throw new IllegalStateException("Load native lib failed: "
                            + fileInfo(file), e);
                }
            }
        }
        // Touch the wrapper class now so static-init failures surface here
        // with a clear message instead of inside recognize().
        try {
            Class.forName("com.googlecode.tesseract.android.TessBaseAPI");
        } catch (Throwable e) {
            throw new IllegalStateException("Tesseract wrapper unavailable", e);
        }
    }

    private static String message(Throwable e) {
        if (e == null) {
            return "unknown";
        }
        String message = e.getMessage();
        return message == null || message.isEmpty()
                ? e.getClass().getSimpleName() : message;
    }

    private static String fileInfo(File file) {
        try {
            return file.getAbsolutePath() + "(exists=" + file.isFile()
                    + ",size=" + file.length() + ")";
        } catch (Throwable ignored) {
            return String.valueOf(file);
        }
    }

    public synchronized void init() throws Exception {
        init(new Config());
    }

    public synchronized void init(Config config) throws Exception {
        close();
        Config resolved = config == null ? new Config() : config;
        pageSegMode = resolved.pageSegMode;
        File tessdataDir = requiredDirectory(resolved.tessdataDir);
        File libDir = requiredDirectory(resolved.libDir);
        for (String part : resolved.lang.split("\\+")) {
            String code = part.startsWith("~") ? part.substring(1) : part;
            requiredFile(new File(tessdataDir, code + ".traineddata").getAbsolutePath());
        }
        loadLibraries(libDir, nativeLibDir());
        TessBaseAPI next = new TessBaseAPI();
        boolean ok;
        try {
            String datapath = tessdataDir.getParentFile().getAbsolutePath() + "/";
            ok = next.init(datapath, resolved.lang, TessBaseAPI.OEM_LSTM_ONLY);
        } catch (Throwable e) {
            try {
                next.recycle();
            } catch (Throwable ignored) {
            }
            throw new IllegalStateException("Tesseract init crashed: "
                    + describe(tessdataDir, resolved.lang), e);
        }
        if (!ok) {
            try {
                next.recycle();
            } catch (Throwable ignored) {
            }
            throw new IllegalStateException("Tesseract init failed: "
                    + describe(tessdataDir, resolved.lang));
        }
        api = next;
    }

    private static String describe(File tessdataDir, String lang) {
        StringBuilder out = new StringBuilder("tessdata=" + tessdataDir);
        for (String part : lang.split("\\+")) {
            String code = part.startsWith("~") ? part.substring(1) : part;
            File file = new File(tessdataDir, code + ".traineddata");
            out.append(' ').append(code).append("(exists=").append(file.isFile())
                    .append(",size=").append(file.length()).append(')');
        }
        return out.toString();
    }

    public synchronized List<TessOcrResult> recognize(Bitmap bitmap) {
        if (api == null) {
            throw new IllegalStateException("Tesseract OCR is not initialized");
        }
        if (bitmap == null || bitmap.isRecycled() || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
            throw new IllegalArgumentException("Tesseract OCR got an empty bitmap");
        }
        Bitmap input = bitmap;
        boolean recycleInput = false;
        if (bitmap.getConfig() != Bitmap.Config.ARGB_8888) {
            try {
                input = bitmap.copy(Bitmap.Config.ARGB_8888, false);
            } catch (Throwable e) {
                throw new IllegalStateException(
                        "Tesseract OCR cannot convert bitmap config " + bitmap.getConfig()
                                + " (" + bitmap.getWidth() + "x" + bitmap.getHeight() + ")", e);
            }
            if (input == null) {
                throw new IllegalStateException(
                        "Tesseract OCR bitmap copy returned null for config " + bitmap.getConfig());
            }
            recycleInput = true;
        }
        try {
            // Downscale large screenshots: Tesseract time scales with pixels,
            // and UI text stays readable at 800px. Bounds are scaled back to
            // display pixels below so taps stay accurate.
            Bitmap scaled = input;
            boolean recycleScaled = false;
            float down = 1f;
            int maxSide = Math.max(input.getWidth(), input.getHeight());
            if (maxSide > MAX_SIDE) {
                down = (float) MAX_SIDE / maxSide;
                int w = Math.max(1, Math.round(input.getWidth() * down));
                int h = Math.max(1, Math.round(input.getHeight() * down));
                try {
                    Bitmap attempt = Bitmap.createScaledBitmap(input, w, h, true);
                    if (attempt != null) {
                        scaled = attempt;
                        recycleScaled = scaled != input;
                    } else {
                        down = 1f;
                    }
                } catch (Throwable ignored) {
                    scaled = input;
                    down = 1f;
                }
            }
            float up = down <= 0 ? 1f : 1f / down;
            try {
                api.setPageSegMode(pageSegMode);
                api.setImage(scaled);
            // Blocking call that runs layout analysis + recognition once;
            // the iterator below only reads the results.
            api.getUTF8Text();
            ArrayList<TessOcrResult> out = new ArrayList<>();
            ResultIterator it = null;
            try {
                it = api.getResultIterator();
                if (it == null) {
                    return out;
                }
                it.begin();
                do {
                    String word = null;
                    try {
                        word = it.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD);
                    } catch (Throwable ignored) {
                    }
                    if (word != null) {
                        word = word.trim();
                    }
                    if (word == null || word.isEmpty()) {
                        continue;
                    }
                    Rect box = null;
                    try {
                        box = it.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_WORD);
                    } catch (Throwable ignored) {
                    }
                    float conf = 0;
                    try {
                        conf = it.confidence(TessBaseAPI.PageIteratorLevel.RIL_WORD) / 100f;
                    } catch (Throwable ignored) {
                    }
                    TessOcrResult result = new TessOcrResult();
                    result.setText(word);
                    if (box != null) {
                        result.setBounds(box);
                        result.scaleBounds(up);
                    }
                    result.setConfidence(Math.max(0, Math.min(1, conf)));
                    out.add(result);
                } while (it.next(TessBaseAPI.PageIteratorLevel.RIL_WORD));
            } finally {
                if (it != null) {
                    try {
                        it.delete();
                    } catch (Throwable ignored) {
                    }
                }
                try {
                    api.clear();
                } catch (Throwable ignored) {
                }
            }
            return out;
        } finally {
            if (recycleScaled) {
                scaled.recycle();
            }
        }
        } finally {
            if (recycleInput) {
                input.recycle();
            }
        }
    }

    @Override
    public synchronized void close() {
        if (api != null) {
            try {
                api.recycle();
            } catch (Throwable ignored) {
            }
            api = null;
        }
    }

    private File requiredDirectory(String path) {
        File file = new File(path);
        if (!file.isDirectory()) {
            throw new IllegalStateException("Missing OCR directory: " + file
                    + " (unified app deploys it on launch; legacy: push tessdata/ there)");
        }
        return file;
    }

    private File requiredFile(String path) {
        File file = new File(path);
        if (!file.isFile()) {
            throw new IllegalStateException("Missing OCR file: " + file
                    + " (unified app deploys it on launch; legacy: push tessdata/ there)");
        }
        return file;
    }

    public static final class Config {
        public String tessdataDir = DEFAULT_TESSDATA_DIR;
        public String libDir = DEFAULT_LIB_DIR;
        public String lang = DEFAULT_LANG;
        public int pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO;
    }
}
