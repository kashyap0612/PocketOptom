package com.pocketoptom;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The optics maths behind the app. Plain Java, no Android — easy to read and to unit test.
 */
public final class EyeOptics {

    private EyeOptics() { }

    /**
     * The visible iris is ~11.7 mm wide in almost every adult (variation about ±0.5 mm).
     * That makes it a ruler we always carry: if the iris is 30 px wide in the photo,
     * then 1 px ≈ 11.7 / 30 mm at the depth of the eyes.
     */
    public static final float IRIS_DIAMETER_MM = 11.7f;

    /** Distance from the front of the eye (pupil) back to the centre the eye rotates around. */
    public static final float PUPIL_TO_ROTATION_CENTRE_MM = 13.0f;

    /**
     * When you look at a phone 40 cm away your eyes turn inwards, so the measured PD is
     * a bit smaller than when looking far away. Glasses for distance need the "far" PD.
     *
     * Each pupil sits on the line from its eye's rotation centre to the camera, so by
     * similar triangles: farPD / nearPD = (distance + 13 mm) / distance.
     */
    public static float farPd(float nearPdMm, float cameraDistanceMm) {
        return nearPdMm * (cameraDistanceMm + PUPIL_TO_ROTATION_CENTRE_MM) / cameraDistanceMm;
    }

    /**
     * Height in screen pixels of a tumbling-E letter for a given logMAR level.
     *
     * Standard: at logMAR 0 (6/6 vision) the whole letter covers 5 minutes of arc.
     * Each +0.1 logMAR makes it 10^0.1 ≈ 1.26× bigger.
     *
     * @param ydpi screen pixels per inch (vertical), from DisplayMetrics
     */
    public static float letterHeightPx(double logMar, float viewingDistanceMm, float ydpi) {
        double arcMinutes = 5.0 * Math.pow(10, logMar);
        double radians = Math.toRadians(arcMinutes / 60.0);
        double heightMm = 2.0 * viewingDistanceMm * Math.tan(radians / 2.0);
        return (float) (heightMm / 25.4 * ydpi);
    }

    /** logMAR → the familiar "6/x" Snellen notation. */
    public static String snellen(double logMar) {
        if (logMar > 1.0 + 1e-6) return "<6/60";
        long denominator = Math.round(6.0 * Math.pow(10, logMar));
        return "6/" + denominator;
    }

    public static String logMarText(double logMar) {
        if (logMar > 1.0 + 1e-6) return "> 1.0";
        return String.format(Locale.US, "%.1f", logMar);
    }

    public static float median(List<Float> values) {
        return percentile(values, 0.5f);
    }

    public static float percentile(List<Float> values, float p) {
        if (values.isEmpty()) return Float.NaN;
        List<Float> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int index = Math.round(p * (sorted.size() - 1));
        return sorted.get(index);
    }
}
