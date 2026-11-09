package com.pocketoptom;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TableLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.card.MaterialCardView;
import com.pocketoptom.ui.Ui;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Screen 4: verdict banner, measurement table, suggestions. "Email report" sends the text version. */
public class ReportActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_report);

        TextView customer = findViewById(R.id.customerText);
        customer.setText(Session.customerName.isEmpty() ? "Screening report"
                : Session.customerName + (Session.age >= 0 ? ", " + Session.age : ""));
        TextView date = findViewById(R.id.dateText);
        date.setText(new SimpleDateFormat("d MMM yyyy · HH:mm", Locale.US).format(new Date())
                + " · PocketOptom");

        showVerdict();
        showMeasurements();
        showSuggestions();

        String report = Recommender.buildReport();
        // Opens an email draft (Gmail etc.) with the report filled in.
        // "mailto:" makes Android offer only email apps.
        findViewById(R.id.shareButton).setOnClickListener(v -> {
            Intent email = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"));
            email.putExtra(Intent.EXTRA_SUBJECT, "Eye screening report"
                    + (Session.customerName.isEmpty() ? "" : " – " + Session.customerName));
            email.putExtra(Intent.EXTRA_TEXT, report);
            try {
                startActivity(email);
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, "No email app found", Toast.LENGTH_SHORT).show();
            }
        });

        findViewById(R.id.newCustomerButton).setOnClickListener(v -> {
            Session.reset();
            finish();
        });
    }

    private void showVerdict() {
        List<String> reasons = Recommender.referralReasons();
        boolean refer = !reasons.isEmpty();
        MaterialCardView card = findViewById(R.id.verdictCard);
        TextView title = findViewById(R.id.verdictTitle);
        TextView text = findViewById(R.id.verdictText);

        card.setCardBackgroundColor(Ui.color(this, refer ? R.color.warn_bg : R.color.ok_bg));
        title.setTextColor(Ui.color(this, refer ? R.color.warn : R.color.ok));
        title.setText(refer ? "⚠  See an optometrist" : "✓  No warning signs found");
        if (refer) {
            StringBuilder sb = new StringBuilder();
            for (String r : reasons) sb.append("• ").append(r).append('\n');
            text.setText(sb.toString().trim());
        } else {
            text.setText("Results are within the normal screening range.");
        }
    }

    private void showMeasurements() {
        TableLayout table = findViewById(R.id.measureTable);
        Ui.headerRow(table, "Test", "Right", "Left");

        if (Session.pdFarMm > 0) {
            Ui.row(table, 0, "Pupillary distance",
                    String.format(Locale.US, "%.1f mm", Session.pdFarMm), "");
            Ui.row(table, 0, "Near PD",
                    String.format(Locale.US, "%.1f mm", Session.pdNearMm),
                    String.format(Locale.US, "±%.1f", Session.pdSpreadMm));
        } else {
            Ui.row(table, 0, "Pupillary distance", "—", "");
        }

        Double r = Session.acuityRightLogMar, l = Session.acuityLeftLogMar;
        Ui.row(table, 0, "Near vision",
                r != null ? EyeOptics.snellen(r) : "—", l != null ? EyeOptics.snellen(l) : "—");
        Ui.row(table, 0, "logMAR",
                r != null ? EyeOptics.logMarText(r) : "—", l != null ? EyeOptics.logMarText(l) : "—");

        if (Session.eyeRightLabel != null) {
            Ui.row(table, 0, "Eye screen",
                    Session.eyeRightLabel, Session.eyeLeftLabel);
            Ui.row(table, 0, "Confidence",
                    String.format(Locale.US, "%.0f%%", Session.eyeRightConfidence * 100),
                    String.format(Locale.US, "%.0f%%", Session.eyeLeftConfidence * 100));
        } else {
            Ui.row(table, 0, "Eye screen", "—", "—");
        }
    }

    private void showSuggestions() {
        List<String> lenses = Recommender.lensSuggestions();
        StringBuilder sb = new StringBuilder();
        for (String s : lenses) sb.append("•  ").append(s).append('\n');
        TextView text = findViewById(R.id.suggestionsText);
        text.setText(lenses.isEmpty() ? "Complete the tests to get suggestions." : sb.toString().trim());
    }
}
