package com.pocketoptom;

import java.util.Locale;

/**
 * Everything we learn about the current customer, shared by all screens.
 *
 * Static fields are the simplest way to share data between Activities. A bigger app
 * would use a ViewModel or a database, but for one customer at a time this is enough.
 * A value of -1 (or null) means "not measured yet".
 */
public final class Session {

    private Session() { }   // no instances — this class only holds static data

    // Entered on the home screen
    public static String customerName = "";
    public static int age = -1;
    public static int screenHoursPerDay = -1;
    public static boolean drivesAtNight = false;

    // Screen 1: pupillary distance
    public static float pdNearMm = -1;      // while looking at the phone
    public static float pdFarMm = -1;       // corrected for looking far away — used for lens fitting
    public static float pdSpreadMm = -1;    // how much the samples varied (±)

    // Screen 2: vision test (logMAR: 0.0 = 6/6 normal, higher = worse)
    public static Double acuityRightLogMar = null;
    public static Double acuityLeftLogMar = null;
    public static float acuityTestDistanceMm = -1;

    // Screen 3: eye photo screen with our own quantised model
    public static String eyeModelName = null;
    public static String eyeRightLabel = null, eyeLeftLabel = null;
    public static float eyeRightConfidence = -1, eyeLeftConfidence = -1;
    public static float eyeRightAbnormal = -1, eyeLeftAbnormal = -1;   // 0..1, -1 = unknown

    // Benchmark screen
    public static String benchmarkSummary = null;

    public static void reset() {
        customerName = "";
        age = -1;
        screenHoursPerDay = -1;
        drivesAtNight = false;
        pdNearMm = pdFarMm = pdSpreadMm = -1;
        acuityRightLogMar = acuityLeftLogMar = null;
        acuityTestDistanceMm = -1;
        eyeModelName = eyeRightLabel = eyeLeftLabel = null;
        eyeRightConfidence = eyeLeftConfidence = -1;
        eyeRightAbnormal = eyeLeftAbnormal = -1;
        // benchmarkSummary is about the phone, not the customer, so we keep it.
    }

    /** One line per step, shown on the home screen. */
    public static String progressSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append(pdFarMm > 0
                ? String.format(Locale.US, "✓ PD: %.1f mm\n", pdFarMm)
                : "○ PD not measured\n");
        sb.append(acuityRightLogMar != null && acuityLeftLogMar != null
                ? String.format(Locale.US, "✓ Vision: R %s, L %s\n",
                    EyeOptics.snellen(acuityRightLogMar), EyeOptics.snellen(acuityLeftLogMar))
                : "○ Vision test not done\n");
        sb.append(eyeRightLabel != null
                ? "✓ Eye screen: R " + eyeRightLabel + ", L " + eyeLeftLabel
                : "○ Eye screen not done");
        return sb.toString();
    }
}
