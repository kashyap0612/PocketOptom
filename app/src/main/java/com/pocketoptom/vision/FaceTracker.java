package com.pocketoptom.vision;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;

import androidx.annotation.Nullable;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mediapipe.framework.image.BitmapExtractor;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult;

import com.pocketoptom.EyeOptics;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs the front camera and MediaPipe Face Landmarker, and turns every frame into a
 * {@link FaceMeasurement} (PD, distance, iris positions).
 *
 * Threads, because they confuse everyone at first:
 *  - Camera frames arrive on {@code cameraExecutor} (a background thread) so the UI never freezes.
 *  - MediaPipe calls {@link #onResult} on its own background thread.
 *  - We hand the result to the screen on the main (UI) thread via {@code mainHandler},
 *    because Android only lets the main thread touch views.
 *
 * Used by PdActivity, AcuityActivity and EyeScreenActivity.
 */
public class FaceTracker {

    public interface Listener {
        /** Called on the main thread. {@code m} is null when no face is visible. */
        void onMeasurement(@Nullable FaceMeasurement m);
    }

    private static final String TAG = "FaceTracker";

    // Face Landmarker returns 478 points. The last 10 are the irises: a centre + 4 edge points each.
    // (MediaPipe numbering: 468-472 = customer's right eye, 473-477 = customer's left eye.)
    private static final int RIGHT_IRIS_CENTRE = 468, RIGHT_IRIS_EDGE_A = 469, RIGHT_IRIS_EDGE_B = 471;
    private static final int LEFT_IRIS_CENTRE = 473, LEFT_IRIS_EDGE_A = 474, LEFT_IRIS_EDGE_B = 476;

    /** If the two irises differ in width by more than this, the head is turned. */
    private static final float MAX_IRIS_ASYMMETRY = 0.12f;

    private final Context context;
    private final Listener listener;
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile FaceLandmarker landmarker;
    private volatile boolean closed = false;
    private float focalPx = -1;
    private long lastTimestampMs = 0;

    public FaceTracker(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    /**
     * Starts camera + face tracking. The camera stops automatically when {@code owner}
     * (the Activity) is destroyed; call {@link #close()} from onDestroy as well.
     *
     * @param previewView where to show the live camera, or null for no preview
     */
    public void start(LifecycleOwner owner, @Nullable PreviewView previewView) {
        landmarker = createLandmarker();
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(context);
        future.addListener(() -> {
            try {
                bindCamera(future.get(), owner, previewView);
            } catch (Exception e) {
                Log.e(TAG, "Could not start camera", e);
            }
        }, ContextCompat.getMainExecutor(context));
    }

    public void close() {
        closed = true;
        // Close MediaPipe on the camera thread so it can't be mid-frame when we close it.
        cameraExecutor.execute(() -> {
            if (landmarker != null) landmarker.close();
        });
        cameraExecutor.shutdown();
    }

    private FaceLandmarker createLandmarker() {
        BaseOptions base = BaseOptions.builder()
                .setModelAssetPath("face_landmarker.task")
                .build();
        FaceLandmarker.FaceLandmarkerOptions options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumFaces(1)
                .setMinFaceDetectionConfidence(0.5f)
                .setMinFacePresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setResultListener(this::onResult)
                .setErrorListener(e -> Log.e(TAG, "MediaPipe error", e))
                .build();
        return FaceLandmarker.createFromOptions(context, options);
    }

    private void bindCamera(ProcessCameraProvider provider, LifecycleOwner owner,
                            @Nullable PreviewView previewView) {
        // Ask for ~1280×960 frames: at 640×480 the iris is only ~13 px wide at 40 cm,
        // which makes PD noisy. Twice the resolution ≈ twice the precision.
        ResolutionSelector resolution = new ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(new ResolutionStrategy(new Size(1280, 960),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build();

        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setResolutionSelector(resolution)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST) // drop frames if we're slow
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build();
        analysis.setAnalyzer(cameraExecutor, this::analyze);

        provider.unbindAll();
        if (previewView != null) {
            Preview preview = new Preview.Builder().build();
            preview.setSurfaceProvider(previewView.getSurfaceProvider());
            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis);
        } else {
            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, analysis);
        }
    }

    /** Runs on cameraExecutor for every frame. */
    private void analyze(ImageProxy image) {
        if (closed || landmarker == null) {
            image.close();
            return;
        }
        Bitmap raw = image.toBitmap();
        int rotation = image.getImageInfo().getRotationDegrees();
        image.close();   // always release the frame quickly, or the camera stalls

        // The sensor is mounted sideways; rotate so the face is upright.
        Bitmap upright = raw;
        if (rotation != 0) {
            Matrix m = new Matrix();
            m.postRotate(rotation);
            upright = Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(), m, true);
        }

        if (focalPx < 0) {
            focalPx = CameraIntrinsics.frontFocalLengthPx(context,
                    Math.max(upright.getWidth(), upright.getHeight()));
        }

        // LIVE_STREAM mode needs strictly increasing timestamps.
        long now = SystemClock.uptimeMillis();
        if (now <= lastTimestampMs) now = lastTimestampMs + 1;
        lastTimestampMs = now;

        landmarker.detectAsync(new BitmapImageBuilder(upright).build(), now);
    }

    /** Called by MediaPipe on its own thread. */
    private void onResult(FaceLandmarkerResult result, MPImage input) {
        if (closed) return;
        FaceMeasurement measurement = null;
        if (!result.faceLandmarks().isEmpty()) {
            Bitmap frame = BitmapExtractor.extract(input);
            measurement = measure(result.faceLandmarks().get(0), frame);
        }
        final FaceMeasurement m = measurement;
        mainHandler.post(() -> {
            if (!closed) listener.onMeasurement(m);
        });
    }

    @Nullable
    private FaceMeasurement measure(List<NormalizedLandmark> points, Bitmap frame) {
        if (points.size() < 478) return null;   // model without iris points
        int w = frame.getWidth();
        int h = frame.getHeight();

        PointF rightCentre = toPixels(points.get(RIGHT_IRIS_CENTRE), w, h);
        PointF leftCentre = toPixels(points.get(LEFT_IRIS_CENTRE), w, h);
        float rightIrisPx = distance(toPixels(points.get(RIGHT_IRIS_EDGE_A), w, h),
                toPixels(points.get(RIGHT_IRIS_EDGE_B), w, h));
        float leftIrisPx = distance(toPixels(points.get(LEFT_IRIS_EDGE_A), w, h),
                toPixels(points.get(LEFT_IRIS_EDGE_B), w, h));
        float irisPx = (rightIrisPx + leftIrisPx) / 2f;
        if (irisPx < 2f) return null;

        float mmPerPixel = EyeOptics.IRIS_DIAMETER_MM / irisPx;          // the iris ruler
        float pdNearMm = distance(rightCentre, leftCentre) * mmPerPixel;
        float distanceMm = focalPx * EyeOptics.IRIS_DIAMETER_MM / irisPx;  // pinhole camera
        boolean facing = Math.abs(rightIrisPx - leftIrisPx) / irisPx < MAX_IRIS_ASYMMETRY;

        return new FaceMeasurement(pdNearMm, distanceMm, irisPx, rightCentre, leftCentre, facing, frame);
    }

    private static PointF toPixels(NormalizedLandmark p, int w, int h) {
        return new PointF(p.x() * w, p.y() * h);   // MediaPipe gives 0..1 coordinates
    }

    private static float distance(PointF a, PointF b) {
        return (float) Math.hypot(a.x - b.x, a.y - b.y);
    }

    /** Square crop around an eye, clamped to the frame. Used for the eye-health model. */
    public static Bitmap cropEye(Bitmap frame, PointF centre, float irisPx) {
        int side = Math.round(irisPx * 4f);   // iris + eyelids + a little skin, like the training photos
        side = Math.min(side, Math.min(frame.getWidth(), frame.getHeight()));
        int left = Math.round(centre.x - side / 2f);
        int top = Math.round(centre.y - side / 2f);
        left = Math.max(0, Math.min(left, frame.getWidth() - side));
        top = Math.max(0, Math.min(top, frame.getHeight() - side));
        return Bitmap.createBitmap(frame, left, top, side, side);
    }
}
