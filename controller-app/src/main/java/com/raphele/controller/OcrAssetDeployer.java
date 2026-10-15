package com.raphele.controller;

import android.content.Context;

import com.raphele.tessocr.TessAssets;

import java.io.File;

/**
 * Deploys the OCR runtime bundled in this unified APK (assets/ocr/tessdata +
 * native libs) to /data/local/tmp/raphele/ocr, where the shell server
 * expects it. Delegates to {@link TessAssets} so the app process and the
 * Shizuku shell process share one implementation.
 */
public final class OcrAssetDeployer {
    public static final String OCR_ROOT = TessAssets.OCR_ROOT;

    private OcrAssetDeployer() {
    }

    public static String deploy(Context context) {
        return TessAssets.deploy(context, false);
    }

    public static String deploy(Context context, boolean force) {
        return TessAssets.deploy(context, force);
    }

    public static String diagnose() {
        try {
            return com.raphele.tessocr.TessOcrEngine.diagnose();
        } catch (Throwable e) {
            return "diagnosis failed: " + new File(OCR_ROOT).getAbsolutePath();
        }
    }
}
