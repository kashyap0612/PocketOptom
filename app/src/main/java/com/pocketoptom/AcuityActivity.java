package com.pocketoptom;

import android.graphics.Color;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.pocketoptom.ui.TumblingEView;
import com.pocketoptom.vision.FaceMeasurement;
import com.pocketoptom.vision.FaceTracker;

import java.util.Locale;
import java.util.Random;

/**
 * Screen 2: near visual acuity with a tumbling E, one eye at a time.
 *
 * The front camera keeps measuring how far the face is, and the letter is resized
 * live so it always covers the correct angle — a printed chart can't do that.
 *
 * Procedure (a simple staircase):
 *  - Start at logMAR 0.5. Show up to 3 letters per level; 2 correct = pass.
 *  - Pass → go 0.1 smaller. Fail → stop; the score is the smallest level passed.
 *  - Failing the very first level → go bigger until one is passed.
 */
public class AcuityActivity extends AppCompatActivity implements FaceTracker.Listener {

    private static final double START_LEVEL = 0.5, STEP = 0.1;
    private static final double SMALLEST_LEVEL = -0.3, BIGGEST_LEVEL = 1.0;
    private static final int TRIALS_PER_LEVEL = 3, CORRECT_TO_PASS = 2;
    private static final float MIN_LETTER_PX = 5f;   // below this the screen can't draw an E accurately
    private static final float DEFAULT_DISTANCE_MM = 400f;

    private FaceTracker tracker;
    private TumblingEView letter;
    private TextView instructionText, distanceText, levelText;
    private Button startButton;
    private float ydpi;
    private final Random random = new Random();

    private float distanceMm = -1;          // smoothed live distance
    private float distanceSumMm = 0;
    private int distanceCount = 0;

    private int eye = 0;                    // 0 = right eye, 1 = left eye
    private final double[] scores = new double[2];
    private boolean testing = false;
    private double level;
    private Double bestPassed;
    private boolean goingUp;
    private int trial, correct, currentDirection;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_acuity);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        letter = findViewById(R.id.letter);
        instructionText = findViewById(R.id.instructionText);
        distanceText = findViewById(R.id.distanceText);
        levelText = findViewById(R.id.levelText);
        startButton = findViewById(R.id.startButton);
        ydpi = getResources().getDisplayMetrics().ydpi;

        findViewById(R.id.upButton).setOnClickListener(v -> answer(TumblingEView.UP));
        findViewById(R.id.rightButton).setOnClickListener(v -> answer(TumblingEView.RIGHT));
        findViewById(R.id.downButton).setOnClickListener(v -> answer(TumblingEView.DOWN));
        findViewById(R.id.leftButton).setOnClickListener(v -> answer(TumblingEView.LEFT));
        findViewById(R.id.cantSeeButton).setOnClickListener(v -> answer(-1));
        startButton.setOnClickListener(v -> onStartPressed());

        letter.setLetterHeightPx(0);
        tracker = new FaceTracker(this, this);
        tracker.start(this, null);   // no preview needed, only the distance
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        tracker.close();
    }

    @Override
    public void onMeasurement(@Nullable FaceMeasurement m) {
        if (m == null) {
            distanceText.setText("Face not visible — using last distance");
            distanceText.setTextColor(Color.rgb(200, 120, 0));
            return;
        }
        // Smooth the jitter: 80% old value + 20% new value.
        distanceMm = distanceMm < 0 ? m.distanceMm : 0.8f * distanceMm + 0.2f * m.distanceMm;
        boolean ok = distanceMm > 300 && distanceMm < 550;
        distanceText.setText(String.format(Locale.US, "Distance %.0f cm%s",
                distanceMm / 10f, ok ? "" : " — aim for about 40 cm"));
        distanceText.setTextColor(ok ? Color.rgb(30, 130, 60) : Color.rgb(200, 120, 0));
        if (testing) {
            distanceSumMm += distanceMm;
            distanceCount++;
            updateLetterSize();
        }
    }

    private void onStartPressed() {
        if (eye < 2 && !testing) {
            startEye();
        } else {
            finish();
        }
    }

    private void startEye() {
        testing = true;
        level = START_LEVEL;
        bestPassed = null;
        goingUp = false;
        startButton.setEnabled(false);
        instructionText.setText(eye == 0
                ? "Cover the LEFT eye. Which way is the E open?"
                : "Cover the RIGHT eye. Which way is the E open?");
        startLevel();
    }

    private void startLevel() {
        trial = 0;
        correct = 0;
        nextLetter();
    }

    private void nextLetter() {
        currentDirection = random.nextInt(4);
        letter.setDirection(currentDirection);
        updateLetterSize();
        levelText.setText(String.format(Locale.US, "Level %s (%s) · letter %d of %d",
                EyeOptics.logMarText(level), EyeOptics.snellen(level), trial + 1, TRIALS_PER_LEVEL));
    }

    private void updateLetterSize() {
        letter.setLetterHeightPx(EyeOptics.letterHeightPx(level, currentDistance(), ydpi));
    }

    private float currentDistance() {
        return distanceMm > 0 ? distanceMm : DEFAULT_DISTANCE_MM;
    }

    private void answer(int direction) {
        if (!testing) return;
        trial++;
        if (direction == currentDirection) correct++;
        int wrong = trial - correct;

        if (correct >= CORRECT_TO_PASS) {
            levelDone(true);
        } else if (wrong > TRIALS_PER_LEVEL - CORRECT_TO_PASS) {
            levelDone(false);   // can no longer reach 2 correct
        } else {
            nextLetter();
        }
    }

    private void levelDone(boolean passed) {
        if (passed) {
            bestPassed = level;
            double next = round1(level - STEP);
            boolean tooSmallToDraw = EyeOptics.letterHeightPx(next, currentDistance(), ydpi) < MIN_LETTER_PX;
            if (goingUp || next < SMALLEST_LEVEL - 1e-9 || tooSmallToDraw) {
                finishEye(level);
                return;
            }
            level = next;
        } else {
            if (bestPassed != null) {
                finishEye(bestPassed);
                return;
            }
            goingUp = true;
            double next = round1(level + STEP);
            if (next > BIGGEST_LEVEL + 1e-9) {
                finishEye(BIGGEST_LEVEL + STEP);   // worse than the biggest letter we show
                return;
            }
            level = next;
        }
        startLevel();
    }

    private void finishEye(double score) {
        testing = false;
        scores[eye] = score;
        letter.setLetterHeightPx(0);
        String eyeName = eye == 0 ? "Right" : "Left";
        levelText.setText(String.format(Locale.US, "%s eye: logMAR %s (%s)",
                eyeName, EyeOptics.logMarText(score), EyeOptics.snellen(score)));
        eye++;
        startButton.setEnabled(true);
        if (eye == 1) {
            instructionText.setText("Right eye done. Now the left eye.");
            startButton.setText("Start — LEFT eye");
        } else {
            Session.acuityRightLogMar = scores[0];
            Session.acuityLeftLogMar = scores[1];
            Session.acuityTestDistanceMm = distanceCount > 0 ? distanceSumMm / distanceCount : DEFAULT_DISTANCE_MM;
            instructionText.setText(String.format(Locale.US, "Done. Right %s · Left %s",
                    EyeOptics.snellen(scores[0]), EyeOptics.snellen(scores[1])));
            startButton.setText("Save & continue");
        }
    }

    /** Avoid floating-point drift like 0.30000000004. */
    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
