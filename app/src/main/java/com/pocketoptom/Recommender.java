package com.pocketoptom;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Turns the measurements in {@link Session} into a report, lens suggestions and
 * referral reasons. Simple, explainable rules — no black box in the advice itself.
 */
public final class Recommender {

    private Recommender() { }

    /** logMAR 0.3 = 6/12. Worse than this is a clear reason for a full eye exam. */
    private static final double REFER_ACUITY = 0.3;
    /** Two eyes differing by 2 lines or more needs checking. */
    private static final double REFER_EYE_DIFFERENCE = 0.2;
    private static final float REFER_ABNORMAL_PROBABILITY = 0.5f;

    public static List<String> referralReasons() {
        List<String> reasons = new ArrayList<>();
        Double r = Session.acuityRightLogMar, l = Session.acuityLeftLogMar;
        if (r == null || l == null) {
            reasons.add("Vision test not completed");
        } else {
            if (r > REFER_ACUITY + 1e-9) reasons.add("Right eye near vision worse than 6/12");
            if (l > REFER_ACUITY + 1e-9) reasons.add("Left eye near vision worse than 6/12");
            if (Math.abs(r - l) >= REFER_EYE_DIFFERENCE - 1e-9) reasons.add("Large difference between the two eyes");
        }
        if (Session.eyeRightAbnormal >= REFER_ABNORMAL_PROBABILITY) {
            reasons.add("Eye photo screen flagged the right eye (" + Session.eyeRightLabel + ")");
        }
        if (Session.eyeLeftAbnormal >= REFER_ABNORMAL_PROBABILITY) {
            reasons.add("Eye photo screen flagged the left eye (" + Session.eyeLeftLabel + ")");
        }
        return reasons;
    }

    public static List<String> lensSuggestions() {
        List<String> s = new ArrayList<>();
        if (Session.pdFarMm > 0) {
            s.add(String.format(Locale.US, "Centre lenses using PD %.1f mm (distance) / %.1f mm (near)",
                    Session.pdFarMm, Session.pdNearMm));
        }
        if (Session.age >= 40) {
            s.add("Age 40+: near focus naturally weakens (presbyopia) — consider progressive or reading lenses");
        }
        Double r = Session.acuityRightLogMar, l = Session.acuityLeftLogMar;
        if (r != null && l != null && Math.max(r, l) > 0.1 + 1e-9 && Math.max(r, l) <= REFER_ACUITY + 1e-9) {
            s.add("Mild reduction in vision — get a full refraction before choosing lens power");
        }
        if (Session.screenHoursPerDay >= 6) {
            s.add("Long screen use: blue-light filtering + anti-reflective coating");
        }
        if (Session.drivesAtNight) {
            s.add("Night driving: anti-reflective coating to cut glare from headlights");
        }
        return s;
    }

    public static String buildReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("POCKETOPTOM SCREENING REPORT\n");
        sb.append(new SimpleDateFormat("d MMM yyyy, HH:mm", Locale.US).format(new Date())).append("\n");
        if (!Session.customerName.isEmpty()) sb.append("Customer: ").append(Session.customerName);
        if (Session.age >= 0) sb.append(", age ").append(Session.age);
        sb.append("\n\nMEASUREMENTS\n");

        if (Session.pdFarMm > 0) {
            sb.append(String.format(Locale.US, "• Pupillary distance: %.1f mm (near %.1f mm, ±%.1f)\n",
                    Session.pdFarMm, Session.pdNearMm, Session.pdSpreadMm));
        } else {
            sb.append("• Pupillary distance: not measured\n");
        }

        if (Session.acuityRightLogMar != null && Session.acuityLeftLogMar != null) {
            sb.append(String.format(Locale.US, "• Near vision at %.0f cm:\n", Session.acuityTestDistanceMm / 10f));
            sb.append(String.format(Locale.US, "    Right eye %s (logMAR %s)\n",
                    EyeOptics.snellen(Session.acuityRightLogMar), EyeOptics.logMarText(Session.acuityRightLogMar)));
            sb.append(String.format(Locale.US, "    Left eye  %s (logMAR %s)\n",
                    EyeOptics.snellen(Session.acuityLeftLogMar), EyeOptics.logMarText(Session.acuityLeftLogMar)));
        } else {
            sb.append("• Vision test: not done\n");
        }

        if (Session.eyeRightLabel != null) {
            sb.append("• Eye photo screen (").append(Session.eyeModelName).append(", on-device):\n");
            sb.append(String.format(Locale.US, "    Right eye: %s (%.0f%%)\n", Session.eyeRightLabel, Session.eyeRightConfidence * 100));
            sb.append(String.format(Locale.US, "    Left eye:  %s (%.0f%%)\n", Session.eyeLeftLabel, Session.eyeLeftConfidence * 100));
        } else {
            sb.append("• Eye photo screen: not done\n");
        }

        List<String> reasons = referralReasons();
        sb.append("\nREFERRAL\n");
        if (reasons.isEmpty()) {
            sb.append("No warning signs found in this screening.\n");
        } else {
            sb.append("Please see an optometrist for a full eye exam:\n");
            for (String reason : reasons) sb.append("• ").append(reason).append("\n");
        }

        List<String> lenses = lensSuggestions();
        if (!lenses.isEmpty()) {
            sb.append("\nLENS & FRAME SUGGESTIONS\n");
            for (String s : lenses) sb.append("• ").append(s).append("\n");
        }

        sb.append("\nThis is a screening aid, not a diagnosis or a prescription. ")
          .append("The vision test checks near vision at arm's length; distance vision ")
          .append("(short-sightedness) needs a full exam. All processing happened on this phone.");
        return sb.toString();
    }
}
