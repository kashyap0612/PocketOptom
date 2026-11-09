package com.pocketoptom;

import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TableLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.pocketoptom.ml.Benchmark;
import com.pocketoptom.ml.TfliteClassifier;
import com.pocketoptom.ui.Ui;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Runs {@link Benchmark} on this phone and shows a headline + a table of every variant. */
public class BenchmarkActivity extends AppCompatActivity {

    /** Kept while the app is open, so the results survive leaving and re-opening this screen. */
    private static List<Benchmark.Row> lastRows;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private TextView statusText;
    private Button runButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_benchmark);

        TextView deviceText = findViewById(R.id.deviceText);
        statusText = findViewById(R.id.statusText);
        runButton = findViewById(R.id.runButton);

        String chip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? " · " + Build.SOC_MODEL : "";
        deviceText.setText(Build.MANUFACTURER + " " + Build.MODEL + chip + " · Android " + Build.VERSION.RELEASE);

        runButton.setOnClickListener(v -> run());
        if (lastRows != null) show(lastRows);
    }

    private void run() {
        runButton.setEnabled(false);
        runButton.setText("Running…");
        executor.execute(() -> {
            List<Benchmark.Row> rows = Benchmark.run(this,
                    message -> runOnUiThread(() -> runButton.setText(message)));
            lastRows = rows;
            Session.benchmarkSummary = Benchmark.format(rows);
            runOnUiThread(() -> {
                show(rows);
                runButton.setEnabled(true);
                runButton.setText("Run again");
            });
        });
    }

    private void show(List<Benchmark.Row> rows) {
        TableLayout table = findViewById(R.id.benchTable);
        table.removeAllViews();
        Ui.headerRow(table, "Model", "Chip", "Size", "Time", "Agree");

        Benchmark.Row baseline = null, best = null;
        for (Benchmark.Row r : rows) {
            if (r.error != null) continue;
            if (baseline == null && r.variant.equals("fp32") && r.accelerator == TfliteClassifier.Accelerator.CPU) baseline = r;
            if (r.variant.equals("int8") && (best == null || r.medianMs < best.medianMs)) best = r;
        }

        for (Benchmark.Row r : rows) {
            String model = r.variant.toUpperCase(Locale.US);
            if (r.error != null) {
                Ui.row(table, Ui.color(this, R.color.text_secondary), model, r.accelerator.name(), "—", "n/a", "—");
                continue;
            }
            int colour = r == best ? Ui.color(this, R.color.ok) : 0;
            Ui.row(table, colour, model, r.accelerator.name(),
                    String.format(Locale.US, "%.1f MB", r.sizeBytes / 1e6),
                    String.format(Locale.US, "%.1f ms", r.medianMs),
                    String.format(Locale.US, "%.0f%%", r.top1Agreement * 100));
        }

        if (baseline != null && best != null) {
            findViewById(R.id.headlineCard).setVisibility(View.VISIBLE);
            ((TextView) findViewById(R.id.smallerValue)).setText(
                    String.format(Locale.US, "%.1f×", (double) baseline.sizeBytes / best.sizeBytes));
            ((TextView) findViewById(R.id.fasterValue)).setText(
                    String.format(Locale.US, "%.1f×", baseline.medianMs / best.medianMs));
            ((TextView) findViewById(R.id.agreeValue)).setText(
                    String.format(Locale.US, "%.0f%%", best.top1Agreement * 100));
            ((TextView) findViewById(R.id.headlineText)).setText(String.format(Locale.US,
                    "INT8 on %s vs FP32 on CPU", best.accelerator.name()));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();   // let a running benchmark finish in the background
    }
}
