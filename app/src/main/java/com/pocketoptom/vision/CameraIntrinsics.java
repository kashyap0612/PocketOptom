package com.pocketoptom.vision;

import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.util.SizeF;

/**
 * Finds the front camera's focal length in pixels.
 *
 * Why we need it: a pinhole camera gives  sizeInPixels = focalPx × realSize / distance.
 * We know the real iris size (11.7 mm) and measure its size in pixels, so
 *     distance = focalPx × 11.7 / irisPx.
 */
public final class CameraIntrinsics {

    private CameraIntrinsics() { }

    /** Used only if the phone does not report its lens data: a typical ~70° selfie camera. */
    private static final double FALLBACK_FIELD_OF_VIEW_DEG = 70.0;

    /**
     * @param imageLongSidePx the longer side of the frames we analyse
     */
    public static float frontFocalLengthPx(Context context, int imageLongSidePx) {
        try {
            CameraManager manager = context.getSystemService(CameraManager.class);
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics c = manager.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (facing == null || facing != CameraCharacteristics.LENS_FACING_FRONT) continue;

                float[] focalMm = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                SizeF sensorMm = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
                if (focalMm != null && focalMm.length > 0 && sensorMm != null) {
                    float sensorLongMm = Math.max(sensorMm.getWidth(), sensorMm.getHeight());
                    // Convert mm on the sensor to pixels in our (possibly downscaled) frame.
                    return focalMm[0] * imageLongSidePx / sensorLongMm;
                }
            }
        } catch (Exception ignored) {
            // fall through to the estimate below
        }
        double halfFov = Math.toRadians(FALLBACK_FIELD_OF_VIEW_DEG / 2.0);
        return (float) (imageLongSidePx / 2.0 / Math.tan(halfFov));
    }
}
