package com.pocketoptom;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TableLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.pocketoptom.ml.ModelConfig;
import com.pocketoptom.ui.Ui;

import java.util.Locale;

/** Home screen: customer details, the four steps (with live status), and a results table. */
public class MainActivity extends AppCompatActivity {

    private EditText nameInput, ageInput, screenHoursInput;
    private CheckBox nightDrivingInput;
    private TextView pdStatus, acuityStatus, eyeStatus;
    private TableLayout resultsTable;

    // Modern way to ask for a permission: register a callback, then launch() it.
    private final ActivityResultLauncher<String> cameraPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (!granted) {
                    Toast.makeText(this, "Camera permission is needed for the eye tests",
                            Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        if (getSupportActionBar() != null) getSupportActionBar().hide();   // the layout has its own header

        nameInput = findViewById(R.id.nameInput);
        ageInput = findViewById(R.id.ageInput);
        screenHoursInput = findViewById(R.id.screenHoursInput);
        nightDrivingInput = findViewById(R.id.nightDrivingInput);
        pdStatus = findViewById(R.id.pdStatus);
        acuityStatus = findViewById(R.id.acuityStatus);
        eyeStatus = findViewById(R.id.eyeStatus);
        resultsTable = findViewById(R.id.resultsTable);

        findViewById(R.id.pdCard).setOnClickListener(v -> open(PdActivity.class, true));
        findViewById(R.id.acuityCard).setOnClickListener(v -> open(AcuityActivity.class, true));
        findViewById(R.id.eyeScreenCard).setOnClickListener(v -> open(EyeScreenActivity.class, true));
        findViewById(R.id.reportCard).setOnClickListener(v -> open(ReportActivity.class, false));
        findViewById(R.id.benchmarkCard).setOnClickListener(v -> open(BenchmarkActivity.class, false));

        String variant = ModelConfig.preferredModel(this);
        if (variant != null) {
            TextView chip = findViewById(R.id.modelChip);
            chip.setText(variant.toUpperCase(Locale.US) + " model");
        }

        if (!hasCameraPermission()) cameraPermission.launch(Manifest.permission.CAMERA);
    }

    /** Called every time we come back to this screen — refresh what's been done. */
    @Override
    protected void onResume() {
        super.onResume();
        nameInput.setText(Session.customerName);
        ageInput.setText(Session.age >= 0 ? String.valueOf(Session.age) : "");
        screenHoursInput.setText(Session.screenHoursPerDay >= 0 ? String.valueOf(Session.screenHoursPerDay) : "");
        nightDrivingInput.setChecked(Session.drivesAtNight);
        refreshStatus();
    }

    private void refreshStatus() {
        boolean pdDone = Session.pdFarMm > 0;
        boolean acuityDone = Session.acuityRightLogMar != null && Session.acuityLeftLogMar != null;
        boolean eyeDone = Session.eyeRightLabel != null;

        setStatus(pdStatus, pdDone ? String.format(Locale.US, "✓ %.1f mm", Session.pdFarMm) : "Start ›", pdDone);
        setStatus(acuityStatus, acuityDone ? "✓ Done" : "Start ›", acuityDone);
        setStatus(eyeStatus, eyeDone ? "✓ Done" : "Start ›", eyeDone);

        resultsTable.removeAllViews();
        Ui.headerRow(resultsTable, "Test", "Right", "Left");
        Ui.row(resultsTable, 0, "Pupillary distance",
                pdDone ? String.format(Locale.US, "%.1f mm", Session.pdFarMm) : "—", "");
        Ui.row(resultsTable, 0, "Near vision",
                acuityDone ? EyeOptics.snellen(Session.acuityRightLogMar) : "—",
                acuityDone ? EyeOptics.snellen(Session.acuityLeftLogMar) : "—");
        Ui.row(resultsTable, 0, "Eye screen",
                eyeDone ? Session.eyeRightLabel : "—",
                eyeDone ? Session.eyeLeftLabel : "—");

        if (pdDone || acuityDone || eyeDone) {
            boolean refer = !Recommender.referralReasons().isEmpty();
            int colour = Ui.color(this, refer ? R.color.warn : R.color.ok);
            Ui.row(resultsTable, colour, "Advice", refer ? "See optometrist" : "No warning signs", "");
        }
    }

    private void setStatus(TextView view, String text, boolean done) {
        view.setText(text);
        view.setTextColor(Ui.color(this, done ? R.color.ok : R.color.brand));
    }

    private void open(Class<?> screen, boolean needsCamera) {
        saveInputs();
        if (needsCamera && !hasCameraPermission()) {
            cameraPermission.launch(Manifest.permission.CAMERA);
            return;
        }
        startActivity(new Intent(this, screen));
    }

    private void saveInputs() {
        Session.customerName = nameInput.getText().toString().trim();
        Session.age = parseIntOr(ageInput, -1);
        Session.screenHoursPerDay = parseIntOr(screenHoursInput, -1);
        Session.drivesAtNight = nightDrivingInput.isChecked();
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static int parseIntOr(EditText field, int fallback) {
        try {
            return Integer.parseInt(field.getText().toString().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
