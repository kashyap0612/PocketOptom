package com.pocketoptom.ml;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.function.Consumer;

/**
 * Measures every model variant (FP32 / FP16 / INT8) on every accelerator (CPU / NNAPI / GPU)
 * on THIS phone: file size, latency, and how closely its outputs match FP32.
 *
 * This is the on-device proof for the quantisation claim — numbers from a laptop
 * don't tell you how a ₹10k ARM phone behaves.
 */
public final class Benchmark {

    private Benchmark() { }

    private static final int WARMUP_RUNS = 5;
    private static final int TIMED_RUNS = 50;
    private static final int TEST_IMAGES = 16;

    public static class Row {
        public final String variant;
        public final TfliteClassifier.Accelerator accelerator;
        public long sizeBytes;
        public double medianMs, p95Ms;
        public double cosineVsReference = Double.NaN;   // 1.0 = identical outputs
        public double top1Agreement = Double.NaN;       // fraction of same top class
        public String error;

        Row(String variant, TfliteClassifier.Accelerator accelerator) {
            this.variant = variant;
            this.accelerator = accelerator;
        }
    }

    /** Slow (seconds). Call from a background thread. */
    public static List<Row> run(Context context, Consumer<String> progress) {
        ModelConfig config = ModelConfig.load(context);
        List<Bitmap> images = testImages(context);
        List<float[]> reference = null;   // outputs of the first model that runs (FP32 on CPU)
        List<Row> rows = new ArrayList<>();

        for (String variant : ModelConfig.VARIANTS) {
            String path = ModelConfig.modelPath(variant);
            if (!ModelConfig.assetExists(context, path)) continue;

            for (TfliteClassifier.Accelerator acc : TfliteClassifier.Accelerator.values()) {
                progress.accept("Running " + variant.toUpperCase(Locale.US) + " on " + acc + "…");
                Row row = new Row(variant, acc);
                try (TfliteClassifier model = new TfliteClassifier(context, path, acc, config)) {
                    row.sizeBytes = model.modelBytes;

                    List<ByteBuffer> inputs = new ArrayList<>();
                    for (Bitmap b : images) inputs.add(model.preprocess(b));

                    List<float[]> outputs = new ArrayList<>();
                    for (ByteBuffer in : inputs) outputs.add(model.run(in));

                    for (int i = 0; i < WARMUP_RUNS; i++) model.run(inputs.get(i % inputs.size()));

                    long[] times = new long[TIMED_RUNS];
                    for (int i = 0; i < TIMED_RUNS; i++) {
                        ByteBuffer in = inputs.get(i % inputs.size());
                        long start = System.nanoTime();
                        model.run(in);
                        times[i] = System.nanoTime() - start;
                    }
                    Arrays.sort(times);
                    row.medianMs = times[TIMED_RUNS / 2] / 1e6;
                    row.p95Ms = times[(int) (TIMED_RUNS * 0.95)] / 1e6;

                    if (reference == null) reference = outputs;
                    compare(row, reference, outputs);
                } catch (Throwable t) {
                    row.error = t.getClass().getSimpleName()
                            + (t.getMessage() != null ? ": " + t.getMessage() : "");
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private static void compare(Row row, List<float[]> reference, List<float[]> outputs) {
        double cosineSum = 0;
        int sameTop = 0;
        for (int i = 0; i < outputs.size(); i++) {
            float[] a = reference.get(i), b = outputs.get(i);
            double dot = 0, na = 0, nb = 0;
            for (int k = 0; k < a.length; k++) {
                dot += a[k] * b[k];
                na += a[k] * a[k];
                nb += b[k] * b[k];
            }
            cosineSum += dot / (Math.sqrt(na) * Math.sqrt(nb) + 1e-12);
            if (argmax(a) == argmax(b)) sameTop++;
        }
        row.cosineVsReference = cosineSum / outputs.size();
        row.top1Agreement = (double) sameTop / outputs.size();
    }

    private static int argmax(float[] v) {
        int best = 0;
        for (int i = 1; i < v.length; i++) if (v[i] > v[best]) best = i;
        return best;
    }

    /** Real eye photos from assets/samples/ if present, padded with fixed random-noise images. */
    private static List<Bitmap> testImages(Context context) {
        List<Bitmap> images = new ArrayList<>();
        try {
            String[] files = context.getAssets().list("samples");
            if (files != null) {
                for (String f : files) {
                    if (images.size() >= TEST_IMAGES) break;
                    try (InputStream in = context.getAssets().open("samples/" + f)) {
                        Bitmap b = BitmapFactory.decodeStream(in);
                        if (b != null) images.add(b);
                    }
                }
            }
        } catch (IOException ignored) {
            // no samples folder
        }
        Random random = new Random(42);   // fixed seed → same "random" images every run
        while (images.size() < TEST_IMAGES) {
            int[] px = new int[224 * 224];
            for (int i = 0; i < px.length; i++) px[i] = 0xFF000000 | random.nextInt(0xFFFFFF);
            images.add(Bitmap.createBitmap(px, 224, 224, Bitmap.Config.ARGB_8888));
        }
        return images;
    }

    /** Human-readable table + headline, for the screen and the report. */
    public static String format(List<Row> rows) {
        if (rows.isEmpty()) {
            return "No models found in assets/models/.\nAdd eye_fp32/fp16/int8.tflite — see README.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "%-5s %-5s %7s %8s %8s %7s %5s%n",
                "model", "accel", "size", "median", "p95", "cosine", "top1"));
        Row baseline = null, bestInt8 = null;
        for (Row r : rows) {
            if (r.error != null) {
                sb.append(String.format(Locale.US, "%-5s %-5s  not supported%n", r.variant, r.accelerator));
                continue;
            }
            sb.append(String.format(Locale.US, "%-5s %-5s %6.2fM %6.1fms %6.1fms %7.4f %4.0f%%%n",
                    r.variant, r.accelerator, r.sizeBytes / 1e6, r.medianMs, r.p95Ms,
                    r.cosineVsReference, r.top1Agreement * 100));
            if (baseline == null && r.variant.equals("fp32") && r.accelerator == TfliteClassifier.Accelerator.CPU) {
                baseline = r;
            }
            if (r.variant.equals("int8") && (bestInt8 == null || r.medianMs < bestInt8.medianMs)) {
                bestInt8 = r;
            }
        }
        if (baseline != null && bestInt8 != null) {
            sb.append(String.format(Locale.US,
                    "%nINT8 on %s vs FP32 on CPU: %.1f× smaller, %.1f× faster, %.0f%% same predictions.",
                    bestInt8.accelerator, (double) baseline.sizeBytes / bestInt8.sizeBytes,
                    baseline.medianMs / bestInt8.medianMs, bestInt8.top1Agreement * 100));
        }
        return sb.toString();
    }
}
