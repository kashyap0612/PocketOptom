package com.pocketoptom.ml;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;
import org.tensorflow.lite.gpu.CompatibilityList;
import org.tensorflow.lite.gpu.GpuDelegate;
import org.tensorflow.lite.nnapi.NnApiDelegate;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Runs an image-classification .tflite model — any of our FP32 / FP16 / INT8 variants —
 * on CPU, NNAPI (the phone's AI accelerator) or GPU.
 *
 * It reads the model's input tensor to adapt automatically:
 *  - float32 input  → feed normalised floats
 *  - uint8/int8 input (full-integer INT8) → feed quantised bytes using the tensor's scale/zero-point
 *  - NHWC (TensorFlow/Keras) or NCHW (PyTorch → ONNX → TFLite) layout
 *
 * Not thread-safe: use one instance from one thread.
 */
public class TfliteClassifier implements AutoCloseable {

    public enum Accelerator { CPU, NNAPI, GPU }

    public static class Prediction {
        public final int index;
        public final float confidence;
        public final float[] probabilities;

        Prediction(float[] probabilities) {
            this.probabilities = probabilities;
            int best = 0;
            for (int i = 1; i < probabilities.length; i++) {
                if (probabilities[i] > probabilities[best]) best = i;
            }
            this.index = best;
            this.confidence = probabilities[best];
        }
    }

    public final long modelBytes;
    public final Accelerator accelerator;

    private final Interpreter interpreter;
    private final ModelConfig config;
    private NnApiDelegate nnApiDelegate;
    private GpuDelegate gpuDelegate;

    private final int inputWidth, inputHeight;
    private final boolean channelsFirst;   // NCHW
    private final DataType inputType, outputType;
    private final float inputScale, outputScale;
    private final int inputZeroPoint, outputZeroPoint;
    private final int numClasses;

    public TfliteClassifier(Context context, String assetPath, Accelerator accelerator,
                            ModelConfig config) throws IOException {
        this.config = config;
        this.accelerator = accelerator;

        MappedByteBuffer model;
        try (AssetFileDescriptor fd = context.getAssets().openFd(assetPath);
             FileInputStream in = new FileInputStream(fd.getFileDescriptor())) {
            model = in.getChannel().map(FileChannel.MapMode.READ_ONLY,
                    fd.getStartOffset(), fd.getDeclaredLength());
            modelBytes = fd.getDeclaredLength();
        }

        Interpreter.Options options = new Interpreter.Options();
        switch (accelerator) {
            case NNAPI:
                nnApiDelegate = new NnApiDelegate();
                options.addDelegate(nnApiDelegate);
                break;
            case GPU:
                CompatibilityList compat = new CompatibilityList();
                if (!compat.isDelegateSupportedOnThisDevice()) {
                    throw new UnsupportedOperationException("GPU delegate not supported on this phone");
                }
                gpuDelegate = new GpuDelegate(compat.getBestOptionsForThisDevice());
                options.addDelegate(gpuDelegate);
                break;
            default:
                options.setNumThreads(4);
        }
        interpreter = new Interpreter(model, options);

        Tensor input = interpreter.getInputTensor(0);
        int[] shape = input.shape();   // [1, H, W, 3] or [1, 3, H, W]
        channelsFirst = shape[1] == 3 && shape[3] != 3;
        inputHeight = channelsFirst ? shape[2] : shape[1];
        inputWidth = channelsFirst ? shape[3] : shape[2];
        inputType = input.dataType();
        inputScale = input.quantizationParams().getScale();
        inputZeroPoint = input.quantizationParams().getZeroPoint();

        Tensor output = interpreter.getOutputTensor(0);
        int[] outShape = output.shape();
        numClasses = outShape[outShape.length - 1];
        outputType = output.dataType();
        outputScale = output.quantizationParams().getScale();
        outputZeroPoint = output.quantizationParams().getZeroPoint();
    }

    public Prediction classify(Bitmap bitmap) {
        return new Prediction(run(preprocess(bitmap)));
    }

    /** Bitmap → the exact bytes the model's input tensor expects. */
    public ByteBuffer preprocess(Bitmap bitmap) {
        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true);
        int[] pixels = new int[inputWidth * inputHeight];
        scaled.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight);

        int bytesPerValue = inputType == DataType.FLOAT32 ? 4 : 1;
        ByteBuffer buffer = ByteBuffer.allocateDirect(pixels.length * 3 * bytesPerValue);
        buffer.order(ByteOrder.nativeOrder());

        if (channelsFirst) {
            for (int c = 0; c < 3; c++) {
                for (int pixel : pixels) put(buffer, channel(pixel, c), c);
            }
        } else {
            for (int pixel : pixels) {
                for (int c = 0; c < 3; c++) put(buffer, channel(pixel, c), c);
            }
        }
        buffer.rewind();
        return buffer;
    }

    /** One inference. Returns class probabilities (dequantised, softmaxed if needed). */
    public float[] run(ByteBuffer input) {
        input.rewind();
        float[] scores = new float[numClasses];
        if (outputType == DataType.FLOAT32) {
            float[][] out = new float[1][numClasses];
            interpreter.run(input, out);
            scores = out[0];
        } else {
            byte[][] out = new byte[1][numClasses];
            interpreter.run(input, out);
            for (int i = 0; i < numClasses; i++) {
                int q = outputType == DataType.UINT8 ? (out[0][i] & 0xFF) : out[0][i];
                scores[i] = (q - outputZeroPoint) * outputScale;
            }
        }
        return looksLikeProbabilities(scores) ? scores : softmax(scores);
    }

    private static int channel(int argb, int c) {
        switch (c) {
            case 0: return (argb >> 16) & 0xFF;   // red
            case 1: return (argb >> 8) & 0xFF;    // green
            default: return argb & 0xFF;          // blue
        }
    }

    private void put(ByteBuffer buffer, int value, int c) {
        float normalised = (value - config.mean[c]) / config.std[c];
        if (inputType == DataType.FLOAT32) {
            buffer.putFloat(normalised);
        } else {
            // Quantise: q = round(real / scale + zeroPoint), clamped to the byte range.
            int q = Math.round(normalised / inputScale + inputZeroPoint);
            if (inputType == DataType.UINT8) {
                q = Math.max(0, Math.min(255, q));
            } else {
                q = Math.max(-128, Math.min(127, q));
            }
            buffer.put((byte) q);
        }
    }

    private static boolean looksLikeProbabilities(float[] s) {
        float sum = 0;
        for (float v : s) {
            if (v < -1e-3f || v > 1.001f) return false;
            sum += v;
        }
        return Math.abs(sum - 1f) < 0.05f;
    }

    private static float[] softmax(float[] logits) {
        float max = Float.NEGATIVE_INFINITY;
        for (float v : logits) max = Math.max(max, v);
        float sum = 0;
        float[] out = new float[logits.length];
        for (int i = 0; i < logits.length; i++) {
            out[i] = (float) Math.exp(logits[i] - max);
            sum += out[i];
        }
        for (int i = 0; i < out.length; i++) out[i] /= sum;
        return out;
    }

    @Override
    public void close() {
        interpreter.close();
        if (nnApiDelegate != null) nnApiDelegate.close();
        if (gpuDelegate != null) gpuDelegate.close();
    }
}
