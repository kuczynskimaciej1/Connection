package com.example.connection;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class FullNetworkActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 101;
    private TextView statusTextView;
    private boolean isServiceRunning = false;

    // Lista uprawnień - Service potrzebuje ich, ale to Activity musi o nie poprosić!
    private final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            // Nowe uprawnienie dla powiadomień w Android 13+ (Tiramisu)
            Manifest.permission.POST_NOTIFICATIONS
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main); // Zakładam, że layout się nie zmienił

        statusTextView = findViewById(R.id.dataTextView); // Użyjemy tego pola do statusu
        Button startButton = findViewById(R.id.startButton);
        Button stopButton = findViewById(R.id.stopButton);
        Button exportButton = findViewById(R.id.exportButton); // Możemy to na razie ukryć/wyłączyć

        // UI Setup
        statusTextView.setText("Gotowy do uruchomienia serwisu.");

        startButton.setOnClickListener(v -> {
            if (checkPermissions()) {
                startNetworkService();
            } else {
                requestPermissions();
            }
        });

        stopButton.setOnClickListener(v -> stopNetworkService());

        // Export jest teraz robiony na bieżąco do pliku przez Service,
        // więc ten przycisk jest chwilowo zbędny, ale może służyć np. do wysłania pliku mailem.
        exportButton.setOnClickListener(v -> Toast.makeText(this, "Dane są zapisywane automatycznie w tle.", Toast.LENGTH_SHORT).show());
    }

    private void startNetworkService() {
        if (isServiceRunning) return;

        Intent serviceIntent = new Intent(this, NetworkCollectionService.class);
        serviceIntent.setAction("START");

        // Kluczowa różnica: Startujemy Foreground Service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, serviceIntent);
        } else {
            startService(serviceIntent);
        }

        isServiceRunning = true;
        statusTextView.setText("STATUS: Zbieranie danych w tle...\nMożesz zminimalizować aplikację.");
        Toast.makeText(this, "Uruchomiono serwis 5G", Toast.LENGTH_SHORT).show();
    }

    private void stopNetworkService() {
        Intent serviceIntent = new Intent(this, NetworkCollectionService.class);
        serviceIntent.setAction("STOP");
        startService(serviceIntent); // Wysyłamy komendę STOP do serwisu

        isServiceRunning = false;
        statusTextView.setText("STATUS: Zatrzymano.");
        Toast.makeText(this, "Zatrzymano zbieranie danych", Toast.LENGTH_SHORT).show();
    }

    // --- Sekcja Uprawnień (Bez zmian - to musi zostać w Activity) ---

    private boolean checkPermissions() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSION_REQUEST_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (checkPermissions()) {
                Toast.makeText(this, "Uprawnienia przyznane. Możesz startować.", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Brak uprawnień! Serwis nie zadziała.", Toast.LENGTH_LONG).show();
            }
        }
    }
}