package com.pocketoptom;

import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.pocketoptom.ml.Benchmark;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Runs {@link Benchmark} on this phone and shows the table. */
public class BenchmarkActivity extends AppCompatActivity {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private TextView resultText;
    private Button runButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_benchmark);

        TextView deviceText = findViewById(R.id.deviceText);
        resultText = findViewById(R.id.resultText);
        runButton = findViewById(R.id.runButton);

        String chip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? " · " + Build.SOC_MODEL : "";
        deviceText.setText("Device: " + Build.MANUFACTURER + " " + Build.MODEL + chip
                + " · Android " + Build.VERSION.RELEASE
                + "\nEach row: 5 warm-up + 50 timed runs. Cosine/top-1 compared with FP32 on CPU.");
        if (Session.benchmarkSummary != null) resultText.setText(Session.benchmarkSummary);

        runButton.setOnClickListener(v -> run());
    }

    private void run() {
        runButton.setEnabled(false);
        resultText.setText("Starting…");
        executor.execute(() -> {
            List<Benchmark.Row> rows = Benchmark.run(this,
                    message -> runOnUiThread(() -> resultText.setText(message)));
            String table = Benchmark.format(rows);
            Session.benchmarkSummary = table;
            runOnUiThread(() -> {
                resultText.setText(table);
                runButton.setEnabled(true);
                runButton.setText("Run again");
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
