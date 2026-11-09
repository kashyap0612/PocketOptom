package com.pocketoptom;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

/** Home screen: customer details + buttons for each step. */
public class MainActivity extends AppCompatActivity {

    private EditText nameInput, ageInput, screenHoursInput;
    private CheckBox nightDrivingInput;
    private TextView statusText;

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

        nameInput = findViewById(R.id.nameInput);
        ageInput = findViewById(R.id.ageInput);
        screenHoursInput = findViewById(R.id.screenHoursInput);
        nightDrivingInput = findViewById(R.id.nightDrivingInput);
        statusText = findViewById(R.id.statusText);

        findViewById(R.id.pdButton).setOnClickListener(v -> open(PdActivity.class, true));
        findViewById(R.id.acuityButton).setOnClickListener(v -> open(AcuityActivity.class, true));
        findViewById(R.id.eyeScreenButton).setOnClickListener(v -> open(EyeScreenActivity.class, true));
        findViewById(R.id.reportButton).setOnClickListener(v -> open(ReportActivity.class, false));
        findViewById(R.id.benchmarkButton).setOnClickListener(v -> open(BenchmarkActivity.class, false));

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
        statusText.setText(Session.progressSummary());
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
