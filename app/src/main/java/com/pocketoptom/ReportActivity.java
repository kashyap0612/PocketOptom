package com.pocketoptom;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

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

        findViewById(R.id.shareButton).setOnClickListener(v -> {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_SUBJECT, "Eye screening report");
            send.putExtra(Intent.EXTRA_TEXT, report);
            startActivity(Intent.createChooser(send, "Share report"));
        });

        findViewById(R.id.newCustomerButton).setOnClickListener(v -> {
            Session.reset();
            finish();
        });
    }
}
