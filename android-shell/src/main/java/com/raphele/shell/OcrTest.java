package com.raphele.shell;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;

import com.raphele.tessocr.TessOcrEngine;
import com.raphele.tessocr.TessOcrResult;

import java.util.List;

final class OcrTest {
    private static final String TEST_IMAGE = "/data/local/tmp/raphele/ocr/test.png";

    private OcrTest() {
    }

    static void run(LogHub logs) {
        Bitmap bitmap = null;
        try {
            logs.logcat(null, "ocr_test_begin");
            logs.logcat(null, "ocr_native_available=" + TessOcrEngine.isNativeAvailable());
            logs.logcat(null, "ocr_diagnosis=" + TessOcrEngine.diagnose());
            bitmap = BitmapFactory.decodeFile(TEST_IMAGE);
            if (bitmap == null) {
                throw new IllegalStateException("Missing OCR test image: " + TEST_IMAGE);
            }
            logs.logcat(null, "ocr_test_image=" + TEST_IMAGE + " size=" + bitmap.getWidth() + "x" + bitmap.getHeight());
            try (TessOcrEngine engine = new TessOcrEngine()) {
                logs.logcat(null, "ocr_init_begin");
                engine.init();
                logs.logcat(null, "ocr_init_done");
                List<TessOcrResult> results = engine.recognize(bitmap);
                logResults(logs, results);
            }
            logs.logcat(null, "ocr_test_end");
        } catch (Throwable e) {
            logs.error("ocr test failed", e);
        } finally {
            if (bitmap != null) {
                bitmap.recycle();
            }
        }
    }

    static void logResults(LogHub logs, List<TessOcrResult> results) {
        logs.logcat(null, "ocr_result_count=" + results.size());
        for (int i = 0; i < results.size(); i++) {
            TessOcrResult result = results.get(i);
            Rect bounds = result.getBounds();
            logs.logcat(null, "ocr[" + i + "] text=" + result.getText()
                    + " confidence=" + result.getConfidence()
                    + " bounds=" + bounds.left + "," + bounds.top + "," + bounds.right + "," + bounds.bottom);
        }
    }
}
