package com.pocketoptom;

import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.view.PreviewView;

import com.pocketoptom.vision.FaceMeasurement;
import com.pocketoptom.vision.FaceTracker;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Screen 1: pupillary distance (PD) from the selfie camera.
 *
 * One frame is noisy, so we collect {@link #SAMPLES_NEEDED} good frames
 * (face straight, at a sensible distance) and take the median.
 */
public class PdActivity extends AppCompatActivity implements FaceTracker.Listener {

    private static final int SAMPLES_NEEDED = 30;
    private static final float MIN_DISTANCE_MM = 250, MAX_DISTANCE_MM = 600;

    private FaceTracker tracker;
    private TextView liveText, resultText;
    private Button measureButton, saveButton;

    private boolean collecting = false;
    private final List<Float> pdSamples = new ArrayList<>();
    private final List<Float> distanceSamples = new ArrayList<>();
    private float pdNear, pdFar, spread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pd);

        liveText = findViewById(R.id.liveText);
        resultText = findViewById(R.id.resultText);
        measureButton = findViewById(R.id.measureButton);
        saveButton = findViewById(R.id.saveButton);
        PreviewView preview = findViewById(R.id.preview);

        measureButton.setOnClickListener(v -> startCollecting());
        saveButton.setOnClickListener(v -> {
            Session.pdNearMm = pdNear;
            Session.pdFarMm = pdFar;
            Session.pdSpreadMm = spread;
            finish();   // back to the home screen
        });

        tracker = new FaceTracker(this, this);
        tracker.start(this, preview);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        tracker.close();
    }

    private void startCollecting() {
        pdSamples.clear();
        distanceSamples.clear();
        collecting = true;
        measureButton.setEnabled(false);
        saveButton.setEnabled(false);
        resultText.setText("");
    }

    @Override
    public void onMeasurement(@Nullable FaceMeasurement m) {
        if (m == null) {
            liveText.setText("No face found — hold the phone in front of the face, good light.");
            return;
        }
        String hint;
        if (!m.facingCamera) hint = "Look straight at the camera";
        else if (m.distanceMm < MIN_DISTANCE_MM) hint = "Move the phone a bit further away";
        else if (m.distanceMm > MAX_DISTANCE_MM) hint = "Bring the phone a bit closer";
        else hint = "Good — hold still";
        liveText.setText(String.format(Locale.US, "Distance %.0f cm · %s", m.distanceMm / 10f, hint));

        if (!collecting) return;
        boolean good = m.facingCamera && m.distanceMm >= MIN_DISTANCE_MM && m.distanceMm <= MAX_DISTANCE_MM;
        if (good) {
            pdSamples.add(m.pdNearMm);
            distanceSamples.add(m.distanceMm);
        }
        resultText.setText(String.format(Locale.US, "Measuring… %d / %d", pdSamples.size(), SAMPLES_NEEDED));
        if (pdSamples.size() >= SAMPLES_NEEDED) finishCollecting();
    }

    private void finishCollecting() {
        collecting = false;
        pdNear = EyeOptics.median(pdSamples);
        float distance = EyeOptics.median(distanceSamples);
        pdFar = EyeOptics.farPd(pdNear, distance);
        // Half the interquartile range: "most samples were within ± this".
        spread = (EyeOptics.percentile(pdSamples, 0.75f) - EyeOptics.percentile(pdSamples, 0.25f)) / 2f;

        resultText.setText(String.format(Locale.US,
                "PD %.1f mm (distance)\nNear PD %.1f mm · ±%.1f mm", pdFar, pdNear, spread));
        measureButton.setText("Measure again");
        measureButton.setEnabled(true);
        saveButton.setEnabled(true);
    }
}
