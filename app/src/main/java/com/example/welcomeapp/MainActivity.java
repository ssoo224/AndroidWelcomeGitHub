package com.example.welcomeapp;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private TextView messageText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        messageText = findViewById(R.id.messageText);

        Button startButton = findViewById(R.id.startButton);
        Button aboutButton = findViewById(R.id.aboutButton);
        Button exitButton = findViewById(R.id.exitButton);

        startButton.setOnClickListener(v -> {
            messageText.setText("أهلاً بك! يمكنك البدء باستخدام التطبيق.");
            Toast.makeText(this, "مرحباً بك", Toast.LENGTH_SHORT).show();
        });

        aboutButton.setOnClickListener(v ->
            messageText.setText("هذا تطبيق تجريبي مبني باستخدام Java و XML.")
        );

        exitButton.setOnClickListener(v -> finish());
    }
}
