package com.pocketoptom.ml;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Settings for the eye model, read from assets/models/:
 *   eye_labels.txt     one class name per line, in the model's output order
 *   model_config.json  {"mean":[r,g,b], "std":[r,g,b]} — how pixels (0..255) were normalised
 *                      during training: value = (pixel - mean) / std
 *
 * ml/train_eye_model.py puts normalisation inside the model, so it writes mean 0 / std 1.
 * For a PyTorch model from quantisation_tool use the ImageNet values:
 *   {"mean":[123.675,116.28,103.53], "std":[58.395,57.12,57.375]}
 */
public final class ModelConfig {

    public static final String DIR = "models/";
    public static final String[] VARIANTS = {"fp32", "fp16", "int8"};

    public final float[] mean;
    public final float[] std;
    public final List<String> labels;

    private ModelConfig(float[] mean, float[] std, List<String> labels) {
        this.mean = mean;
        this.std = std;
        this.labels = labels;
    }

    public static String modelPath(String variant) {
        return DIR + "eye_" + variant + ".tflite";
    }

    /** Best model to use for screening: smallest that exists (INT8 first). */
    public static String preferredModel(Context context) {
        for (int i = VARIANTS.length - 1; i >= 0; i--) {
            if (assetExists(context, modelPath(VARIANTS[i]))) return VARIANTS[i];
        }
        return null;
    }

    public static ModelConfig load(Context context) {
        float[] mean = {0f, 0f, 0f};
        float[] std = {1f, 1f, 1f};
        try {
            JSONObject json = new JSONObject(readAsset(context, DIR + "model_config.json"));
            mean = toFloats(json.getJSONArray("mean"));
            std = toFloats(json.getJSONArray("std"));
        } catch (Exception ignored) {
            // no config file → raw pixels, which is what our training script produces
        }
        List<String> labels = new ArrayList<>();
        try {
            for (String line : readAsset(context, DIR + "eye_labels.txt").split("\n")) {
                if (!line.trim().isEmpty()) labels.add(line.trim());
            }
        } catch (IOException ignored) {
            // labels will show as "class 0", "class 1", ...
        }
        return new ModelConfig(mean, std, labels);
    }

    public String label(int index) {
        return index < labels.size() ? labels.get(index) : "class " + index;
    }

    /** Index of the "normal"/"healthy" class, or -1 if the model has none. */
    public int normalClassIndex() {
        for (int i = 0; i < labels.size(); i++) {
            String l = labels.get(i).toLowerCase();
            if (l.contains("normal") || l.contains("healthy")) return i;
        }
        return -1;
    }

    public static boolean assetExists(Context context, String path) {
        try (InputStream ignored = context.getAssets().open(path)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static String readAsset(Context context, String path) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(context.getAssets().open(path), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static float[] toFloats(JSONArray a) throws Exception {
        float[] out = new float[a.length()];
        for (int i = 0; i < out.length; i++) out[i] = (float) a.getDouble(i);
        return out;
    }
}
