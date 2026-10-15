package com.raphele.tessocr;

import android.graphics.Point;
import android.graphics.Rect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One OCR text item (a word) with display-local bounds and confidence 0..1.
 * Same shape as the old Paddle result so automation code needs no changes.
 */
public final class TessOcrResult {
    private final ArrayList<Point> points = new ArrayList<>();
    private Rect bounds = new Rect();
    private String text = "";
    private float confidence;

    public void setBounds(Rect box) {
        if (box == null) {
            bounds = new Rect();
        } else {
            bounds = new Rect(box);
        }
        points.clear();
        if (!bounds.isEmpty()) {
            points.add(new Point(bounds.left, bounds.top));
            points.add(new Point(bounds.right, bounds.top));
            points.add(new Point(bounds.right, bounds.bottom));
            points.add(new Point(bounds.left, bounds.bottom));
        }
    }

    void addPoint(int x, int y) {
        points.add(new Point(x, y));
    }

    void scaleBounds(float factor) {
        if (factor == 1f || factor <= 0 || bounds.isEmpty()) {
            return;
        }
        setBounds(new Rect(
                Math.round(bounds.left * factor),
                Math.round(bounds.top * factor),
                Math.round(bounds.right * factor),
                Math.round(bounds.bottom * factor)));
    }

    public List<Point> getPoints() {
        return Collections.unmodifiableList(points);
    }

    public List<Integer> getWordIndices() {
        return Collections.emptyList();
    }

    public Rect getBounds() {
        return new Rect(bounds);
    }

    public String getText() {
        return text;
    }

    void setText(String text) {
        this.text = text == null ? "" : text;
    }

    public float getConfidence() {
        return confidence;
    }

    void setConfidence(float confidence) {
        this.confidence = confidence;
    }

    public int getClsIndex() {
        return -1;
    }

    public float getClsConfidence() {
        return 0;
    }
}
