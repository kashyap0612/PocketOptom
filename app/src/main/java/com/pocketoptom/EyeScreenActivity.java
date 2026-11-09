package com.pocketoptom;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.view.PreviewView;

import com.pocketoptom.ml.ModelConfig;
import com.pocketoptom.ml.TfliteClassifier;
import com.pocketoptom.vision.FaceMeasurement;
import com.pocketoptom.vision.FaceTracker;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Screen 3: crop each eye using the iris landmarks and run OUR quantised model on it.
 *
 * The model runs on {@code modelExecutor}, a background thread: inference takes tens of
 * milliseconds and would make the UI stutter if it ran on the main thread.
 */
public class EyeScreenActivity extends AppCompatActivity implements FaceTracker.Listener {

    private FaceTracker tracker;
    private final ExecutorService modelExecutor = Executors.newSingleThreadExecutor();
    private TfliteClassifier classifier;     // only touched on modelExecutor
    private ModelConfig config;
    private String variant;

    private ImageView rightEyeImage, leftEyeImage;
    private TextView resultText;
    private Button captureButton, saveButton;
    private FaceMeasurement latest;

    private String rightLabel, leftLabel;
    private float rightConfidence, leftConfidence, rightAbnormal, leftAbnormal;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_eye_screen);

        rightEyeImage = findViewById(R.id.rightEyeImage);
        leftEyeImage = findViewById(R.id.leftEyeImage);
        resultText = findViewById(R.id.resultText);
        captureButton = findViewById(R.id.captureButton);
        saveButton = findViewById(R.id.saveButton);
        PreviewView preview = findViewById(R.id.preview);

        captureButton.setOnClickListener(v -> capture());
        saveButton.setOnClickListener(v -> save());
        captureButton.setEnabled(false);

        loadModel();
        tracker = new FaceTracker(this, this);
        tracker.start(this, preview);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        tracker.close();
        modelExecutor.execute(() -> {
            if (classifier != null) classifier.close();
        });
        modelExecutor.shutdown();
    }

    private void loadModel() {
        variant = ModelConfig.preferredModel(this);
        if (variant == null) {
            resultText.setText("No eye model found in assets/models/.\n"
                    + "Train one with ml/train_eye_model.py (see README).");
            return;
        }
        modelExecutor.execute(() -> {
            try {
                config = ModelConfig.load(this);
                classifier = new TfliteClassifier(this, ModelConfig.modelPath(variant),
                        TfliteClassifier.Accelerator.CPU, config);
                runOnUiThread(() -> {
                    captureButton.setEnabled(true);
                    resultText.setText("Model ready: eye_" + variant + " ("
                            + String.format(Locale.US, "%.1f MB", classifier.modelBytes / 1e6) + ")");
                });
            } catch (Exception e) {
                runOnUiThread(() -> resultText.setText("Could not load model: " + e.getMessage()));
            }
        });
    }

    @Override
    public void onMeasurement(@Nullable FaceMeasurement m) {
        latest = m;
    }

    private void capture() {
        FaceMeasurement m = latest;
        if (m == null || !m.facingCamera) {
            Toast.makeText(this, "Face the camera straight on, then try again", Toast.LENGTH_SHORT).show();
            return;
        }
        Bitmap right = FaceTracker.cropEye(m.frame, m.rightIrisPx, m.irisPx);
        Bitmap left = FaceTracker.cropEye(m.frame, m.leftIrisPx, m.irisPx);
        rightEyeImage.setImageBitmap(right);
        leftEyeImage.setImageBitmap(left);
        resultText.setText("Analysing…");
        captureButton.setEnabled(false);

        modelExecutor.execute(() -> {
            saveCrop(right, "R");
            saveCrop(left, "L");
            long start = System.nanoTime();
            TfliteClassifier.Prediction r = classifier.classify(right);
            TfliteClassifier.Prediction l = classifier.classify(left);
            double ms = (System.nanoTime() - start) / 1e6 / 2;

            rightLabel = config.label(r.index);
            leftLabel = config.label(l.index);
            rightConfidence = r.confidence;
            leftConfidence = l.confidence;
            rightAbnormal = abnormalProbability(r);
            leftAbnormal = abnormalProbability(l);

            runOnUiThread(() -> {
                resultText.setText(String.format(Locale.US,
                        "Right eye: %s (%.0f%%)\nLeft eye: %s (%.0f%%)\n%.0f ms per eye on CPU · eye_%s",
                        rightLabel, rightConfidence * 100, leftLabel, leftConfidence * 100, ms, variant));
                captureButton.setEnabled(true);
                captureButton.setText("Capture again");
                saveButton.setEnabled(true);
            });
        });
    }

    /**
     * Keeps a copy of what the model saw, in the app's private storage (files/eye_crops/).
     * Useful for debugging and for collecting real phone photos to improve the model.
     * Read them with: adb exec-out run-as com.pocketoptom ls files/eye_crops
     */
    private void saveCrop(Bitmap crop, String eye) {
        File dir = new File(getFilesDir(), "eye_crops");
        if (!dir.exists() && !dir.mkdirs()) return;
        File file = new File(dir, System.currentTimeMillis() + "_" + eye + ".jpg");
        try (FileOutputStream out = new FileOutputStream(file)) {
            crop.compress(Bitmap.CompressFormat.JPEG, 95, out);
        } catch (IOException ignored) {
            // debugging aid only — never block the screening
        }
    }

    /** 1 − P(normal) if the model has a "normal" class, else −1 (unknown). */
    private float abnormalProbability(TfliteClassifier.Prediction p) {
        int normal = config.normalClassIndex();
        return normal >= 0 ? 1f - p.probabilities[normal] : -1f;
    }

    private void save() {
        Session.eyeModelName = "eye_" + variant;
        Session.eyeRightLabel = rightLabel;
        Session.eyeLeftLabel = leftLabel;
        Session.eyeRightConfidence = rightConfidence;
        Session.eyeLeftConfidence = leftConfidence;
        Session.eyeRightAbnormal = rightAbnormal;
        Session.eyeLeftAbnormal = leftAbnormal;
        finish();
    }
}
