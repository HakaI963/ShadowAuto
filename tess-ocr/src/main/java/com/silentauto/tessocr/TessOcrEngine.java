package com.silentauto.tessocr;

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
    public static final String DEFAULT_ROOT = "/data/local/tmp/shadowauto/ocr";
    public static final String DEFAULT_TESSDATA_DIR = DEFAULT_ROOT + "/tessdata";
    public static final String DEFAULT_LIB_DIR = DEFAULT_ROOT + "/lib/arm64-v8a";
    public static final String DEFAULT_LANG = "chi_sim+eng";

    /** Dependency order: C++ runtime, image codecs, leptonica, tesseract. */
    static final String[] LIB_LOAD_NAMES = {"c++_shared", "jpgt", "pngt", "lept", "tess"};
    static final String[] LANG_FILES = {"chi_sim.traineddata", "eng.traineddata"};

    private TessBaseAPI api;
    private int pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO;

    public static boolean isNativeAvailable() {
        try {
            loadLibraries(new File(DEFAULT_LIB_DIR));
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static String diagnose() {
        File root = new File(DEFAULT_ROOT);
        if (!root.isDirectory()) {
            return "ocr root missing: " + root + " (nothing deployed yet)";
        }
        StringBuilder missing = new StringBuilder();
        for (String name : LANG_FILES) {
            check(missing, new File(root, "tessdata/" + name));
        }
        for (String name : LIB_LOAD_NAMES) {
            check(missing, new File(root, "lib/arm64-v8a/" + System.mapLibraryName(name)));
        }
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
            missing.append(file.getAbsolutePath().substring(DEFAULT_ROOT.length() + 1));
        }
    }

    /**
     * Makes the native libs loadable in this process. Prefers
     * {@code System.loadLibrary} (works when the host sets up the app native
     * path, e.g. Shizuku UserService), falls back to the absolute copies
     * under {@code libDir} (shell/app_process flows).
     */
    static void loadLibraries(File libDir) {
        for (String name : LIB_LOAD_NAMES) {
            boolean loaded = false;
            try {
                System.loadLibrary(name);
                loaded = true;
            } catch (Throwable ignored) {
            }
            if (!loaded) {
                File file = new File(libDir, System.mapLibraryName(name));
                if (!file.isFile()) {
                    throw new IllegalStateException("Missing native lib: " + file);
                }
                try {
                    System.load(file.getAbsolutePath());
                } catch (Throwable e) {
                    throw new IllegalStateException("Load native lib failed: " + file, e);
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
        loadLibraries(libDir);
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
            api.setPageSegMode(pageSegMode);
            api.setImage(input);
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
