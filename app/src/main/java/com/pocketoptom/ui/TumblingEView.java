package com.pocketoptom.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * Draws one "tumbling E" — the standard vision-test letter for people who can't read.
 *
 * The E fits a 5×5 grid: a vertical bar on the left and three horizontal bars,
 * each one grid unit thick. The customer says which way the open side points.
 */
public class TumblingEView extends View {

    public static final int RIGHT = 0, DOWN = 1, LEFT = 2, UP = 3;

    private final Paint paint = new Paint();
    private float letterHeightPx = 100f;
    private int direction = RIGHT;

    public TumblingEView(Context context) {
        this(context, null);
    }

    public TumblingEView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        paint.setColor(Color.BLACK);
        paint.setAntiAlias(false);   // sharp edges matter for tiny letters
        setBackgroundColor(Color.WHITE);   // always black on white, even in dark mode
    }

    public void setLetterHeightPx(float px) {
        letterHeightPx = px;
        invalidate();   // ask Android to call onDraw again
    }

    /** RIGHT, DOWN, LEFT or UP — the side the E opens towards. */
    public void setDirection(int direction) {
        this.direction = direction;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float unit = letterHeightPx / 5f;
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float left = cx - 2.5f * unit;
        float top = cy - 2.5f * unit;

        canvas.save();
        canvas.rotate(direction * 90f, cx, cy);   // drawn opening RIGHT, then rotated clockwise
        canvas.drawRect(left, top, left + unit, top + 5 * unit, paint);              // spine
        for (int bar = 0; bar < 3; bar++) {
            float barTop = top + bar * 2 * unit;
            canvas.drawRect(left, barTop, left + 5 * unit, barTop + unit, paint);   // arms
        }
        canvas.restore();
    }
}
