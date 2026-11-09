package com.pocketoptom.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.pocketoptom.R;

/**
 * Small helpers to build good-looking tables in code (used by Home, Report and Benchmark).
 * Building rows in Java is easier than XML when the number of rows depends on the data.
 */
public final class Ui {

    private Ui() { }

    public static int dp(Context c, float dp) {
        return Math.round(dp * c.getResources().getDisplayMetrics().density);
    }

    public static int color(Context c, int colorRes) {
        return ContextCompat.getColor(c, colorRes);
    }

    /** Grey, small-caps-style header row. */
    public static void headerRow(TableLayout table, String... cells) {
        Context c = table.getContext();
        TableRow row = new TableRow(c);
        for (int i = 0; i < cells.length; i++) {
            TextView t = cell(c, cells[i], i == 0);
            t.setTextSize(12);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setTextColor(color(c, R.color.text_secondary));
            t.setAllCaps(true);
            row.addView(t);
        }
        table.addView(row);
        divider(table);
    }

    /** Normal data row. Pass valueColor = 0 to use the default text colour. */
    public static void row(TableLayout table, int valueColor, String... cells) {
        Context c = table.getContext();
        TableRow row = new TableRow(c);
        for (int i = 0; i < cells.length; i++) {
            TextView t = cell(c, cells[i], i == 0);
            t.setTextSize(15);
            if (i == 0) {
                t.setTextColor(color(c, R.color.text_secondary));
            } else {
                t.setTypeface(Typeface.DEFAULT_BOLD);
                t.setTextColor(valueColor != 0 ? valueColor : color(c, R.color.text_primary));
            }
            row.addView(t);
        }
        table.addView(row);
        divider(table);
    }

    private static TextView cell(Context c, String text, boolean first) {
        TextView t = new TextView(c);
        t.setText(text);
        int pad = dp(c, 10);
        t.setPadding(first ? 0 : pad, pad, first ? pad : 0, pad);
        t.setGravity(first ? Gravity.START : Gravity.END);
        return t;
    }

    private static void divider(TableLayout table) {
        View line = new View(table.getContext());
        line.setBackgroundColor(color(table.getContext(), R.color.divider));
        table.addView(line, new TableLayout.LayoutParams(TableLayout.LayoutParams.MATCH_PARENT, 1));
    }
}
