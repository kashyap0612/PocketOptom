package com.pocketoptom;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/** Screen 4: the report + recommendation, shareable as text (WhatsApp, SMS, email…). */
public class ReportActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_report);

        String report = Recommender.buildReport();
        TextView reportText = findViewById(R.id.reportText);
        reportText.setText(report);

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
}
