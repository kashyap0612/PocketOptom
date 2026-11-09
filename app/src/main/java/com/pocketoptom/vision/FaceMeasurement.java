package com.pocketoptom.vision;

import android.graphics.Bitmap;
import android.graphics.PointF;

/**
 * What one camera frame tells us about the eyes. Lengths are in millimetres,
 * unless the name ends in "Px" (pixels in the camera frame).
 *
 * "Right/left" mean the customer's own right and left eye.
 */
public class FaceMeasurement {

    public final float pdNearMm;        // pupil-to-pupil distance while looking at the phone
    public final float distanceMm;      // camera-to-eyes distance
    public final float irisPx;          // average iris width in the frame
    public final PointF rightIrisPx;    // centre of the customer's right iris
    public final PointF leftIrisPx;     // centre of the customer's left iris
    public final boolean facingCamera;  // false if the head is turned (PD would be wrong)
    public final Bitmap frame;          // the upright camera frame these numbers came from

    public FaceMeasurement(float pdNearMm, float distanceMm, float irisPx,
                           PointF rightIrisPx, PointF leftIrisPx,
                           boolean facingCamera, Bitmap frame) {
        this.pdNearMm = pdNearMm;
        this.distanceMm = distanceMm;
        this.irisPx = irisPx;
        this.rightIrisPx = rightIrisPx;
        this.leftIrisPx = leftIrisPx;
        this.facingCamera = facingCamera;
        this.frame = frame;
    }
}
