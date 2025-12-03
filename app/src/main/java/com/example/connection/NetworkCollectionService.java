package com.example.connection;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationManager;
import android.net.TrafficStats;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.telephony.CellIdentityNr;
import android.telephony.CellInfo;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoNr;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellSignalStrengthNr;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyDisplayInfo;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

/**
 * NetworkCollectionService
 * Główny serwis zbierający dane telemetryczne 5G/LTE, parametry środowiskowe
 * oraz wykonujący wnioskowanie (Inference) modelu ML na urządzeniu (Edge AI).
 */
public class NetworkCollectionService extends Service {

    // --- STAŁE ---
    private static final String TAG = "NetworkService";
    private static final String CHANNEL_ID = "NetworkMonitorChannel";
    private static final int NOTIFICATION_ID = 1;
    private static final long POLL_INTERVAL_MS = 1000; // Częstotliwość próbkowania (1s)
    private static final int AI_WINDOW_SIZE = 10;      // Rozmiar okna przesuwnego dla modelu
    private static final float AI_ANOMALY_THRESHOLD = 0.15f;

    // --- KOMPONENTY SYSTEMOWE ---
    private TelephonyManager telephonyManager;
    private LocationManager locationManager;
    private SensorManager sensorManager;
    private PowerManager.WakeLock wakeLock;
    private TelephonyCallback telephonyCallback;

    // --- WĄTKI I HANDLERY ---
    private HandlerThread workerThread;
    private Handler workerHandler;
    private Runnable pollerTask;

    // --- ZMIENNE STANU ---
    private FileOutputStream fileOutputStream;
    private boolean isLogging = false;
    private int currentDisplayNetworkType = TelephonyManager.NETWORK_TYPE_UNKNOWN;

    // --- SENSORY I METRYKI ---
    private float currentLightLux = -1.0f;
    private long lastRxBytes = 0;
    private long lastTxBytes = 0;
    private long lastTrafficTime = 0;

    // --- MODUŁ AI ---
    private AnomalyDetector anomalyDetector;
    private final LinkedList<float[]> dataWindow = new LinkedList<>();

    // --- NASŁUCHIWANIE SENSORÓW ---
    private final SensorEventListener lightListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            currentLightLux = event.values[0];
        }
        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) { }
    };

    // ============================================================================================
    // CYKL ŻYCIA SERWISU
    // ============================================================================================

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Inicjalizacja serwisu...");

        setupNotificationChannel();
        setupPowerManagement();
        setupThreads();
        setupSystemServices();
        setupAiModule();
        setupLogFile();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification());

        if (!isLogging) {
            registerCallbacks();
            startActivePolling();
            isLogging = true;
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Zatrzymywanie serwisu...");

        // Zwolnienie zasobów w odwrotnej kolejności
        if (workerHandler != null && pollerTask != null) workerHandler.removeCallbacks(pollerTask);
        if (sensorManager != null) sensorManager.unregisterListener(lightListener);
        if (telephonyCallback != null) telephonyManager.unregisterTelephonyCallback(telephonyCallback);
        if (anomalyDetector != null) anomalyDetector.close();
        if (workerThread != null) workerThread.quitSafely();

        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.d(TAG, "WakeLock zwolniony.");
        }

        try {
            if (fileOutputStream != null) fileOutputStream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ============================================================================================
    // KONFIGURACJA (SETUP)
    // ============================================================================================

    private void setupNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Network Monitor Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private void setupPowerManagement() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NetworkCollectionService::Wakelock");
        wakeLock.acquire(); // Utrzymuj CPU włączone
    }

    private void setupThreads() {
        workerThread = new HandlerThread("NetworkCollectorWorker");
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());
    }

    private void setupSystemServices() {
        telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);

        // Konfiguracja sensora światła
        if (sensorManager != null) {
            Sensor lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
            if (lightSensor != null) {
                sensorManager.registerListener(lightListener, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);
            }
        }

        // Inicjalizacja statystyk ruchu
        lastRxBytes = TrafficStats.getMobileRxBytes();
        lastTxBytes = TrafficStats.getMobileTxBytes();
        lastTrafficTime = System.currentTimeMillis();
    }

    private void setupAiModule() {
        try {
            anomalyDetector = new AnomalyDetector(this);
            Log.d(TAG, "AI: Model załadowany pomyślnie.");
        } catch (IOException e) {
            Log.e(TAG, "AI: Błąd ładowania modelu TFLite!", e);
        }
    }

    private void setupLogFile() {
        try {
            File dir = getExternalFilesDir(null);
            String filename = "data_ml_ready_" + System.currentTimeMillis() + ".jsonl";
            fileOutputStream = new FileOutputStream(new File(dir, filename), true);
        } catch (IOException e) {
            Log.e(TAG, "Błąd tworzenia pliku logów", e);
        }
    }

    // ============================================================================================
    // LOGIKA ZBIERANIA DANYCH (POLLING)
    // ============================================================================================

    private void registerCallbacks() {
        if (!hasPermissions()) return;

        telephonyCallback = new MyTelephonyCallback();
        // Executor delegujący do wątku roboczego
        Executor serviceExecutor = command -> {
            if (workerHandler != null) workerHandler.post(command);
        };
        telephonyManager.registerTelephonyCallback(serviceExecutor, telephonyCallback);
    }

    private void startActivePolling() {
        pollerTask = new Runnable() {
            @Override
            public void run() {
                forceModemUpdate();
                if (workerHandler != null) {
                    workerHandler.postDelayed(this, POLL_INTERVAL_MS);
                }
            }
        };
        workerHandler.post(pollerTask);
    }

    private void forceModemUpdate() {
        // Sprawdzenie bezpieczeństwa - bez uprawnień kończymy działanie tej metody
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Brak uprawnień lokalizacyjnych - pomijam Active Poll");
            return;
        }

        // Dla Androida 10+ (Q) wymuszamy odświeżenie
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Executor executor = command -> {
                if (workerHandler != null) workerHandler.post(command);
            };

            try {
                telephonyManager.requestCellInfoUpdate(executor, new TelephonyManager.CellInfoCallback() {
                    @Override
                    public void onCellInfo(@NonNull List<CellInfo> cellInfo) {
                        processAndSaveData(cellInfo, "ActivePoll");
                    }

                    @Override
                    public void onError(int errorCode, @Nullable Throwable detail) {
                        Log.w(TAG, "Błąd modemu (ActivePoll code): " + errorCode);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Wyjątek przy requestCellInfoUpdate", e);
            }
        } else {
            // Dla starszych Androidów bierzemy dane z cache
            processAndSaveData(telephonyManager.getAllCellInfo(), "LegacyPoll");
        }
    }

    // ============================================================================================
    // PRZETWARZANIE I ZAPIS DANYCH
    // ============================================================================================

    private void processAndSaveData(List<CellInfo> cellInfoList, String trigger) {
        if (!hasPermissions()) return;

        try {
            JSONObject json = new JSONObject();
            long now = System.currentTimeMillis();

            // 1. Metadane podstawowe
            json.put("timestamp_epoch", now);
            json.put("timestamp_human", new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(new Date(now)));
            json.put("trigger", trigger);

            // 2. Zbieranie danych telemetrycznych (Bateria, GPS, Ruch, Światło)
            gatherTelemetry(json);

            // 3. Przetwarzanie komórek i logika AI
            if (cellInfoList != null) {
                processCells(cellInfoList, json, now);
            }

            // 4. Zapis do pliku
            writeToJsonFile(json);

        } catch (Exception e) {
            Log.e(TAG, "Błąd w pętli przetwarzania danych", e);
        }
    }
    @android.annotation.SuppressLint("MissingPermission")
    private void gatherTelemetry(JSONObject json) throws JSONException {
        // Bateria
        BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
        if (bm != null) {
            json.put("battery_level", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY));
        }

        // Stan sieci (Teraz kod jest czysty)
        json.put("network_type_raw", telephonyManager.getDataNetworkType());
        json.put("network_type_refined", getRefinedNetworkType(telephonyManager.getDataNetworkType(), currentDisplayNetworkType));
        json.put("is_5g_nsa", currentDisplayNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA);

        // Światło (Lux)
        json.put("light_lux", currentLightLux);

        // GPS (Prędkość) - tu zostawiamy IF, bo to osobne uprawnienie lokalizacyjne
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            Location loc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (loc == null) loc = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);

            if (loc != null) {
                json.put("speed_kmh", loc.getSpeed() * 3.6);
                json.put("gps_lat", loc.getLatitude());
                json.put("gps_lng", loc.getLongitude());
            } else {
                json.put("speed_kmh", 0.0);
            }
        }

        // Ruch sieciowy
        long currentRx = TrafficStats.getMobileRxBytes();
        long currentTx = TrafficStats.getMobileTxBytes();

        if (currentRx == TrafficStats.UNSUPPORTED) currentRx = 0;
        if (currentTx == TrafficStats.UNSUPPORTED) currentTx = 0;

        // Obliczamy deltę tylko jeśli to nie jest pierwszy pomiar
        long deltaRx = 0;
        long deltaTx = 0;
        if (lastRxBytes > 0 && currentRx >= lastRxBytes) {
            deltaRx = currentRx - lastRxBytes;
        }
        if (lastTxBytes > 0 && currentTx >= lastTxBytes) {
            deltaTx = currentTx - lastTxBytes;
        }

        json.put("traffic_rx_bytes", deltaRx);
        json.put("traffic_tx_bytes", deltaTx);

        lastRxBytes = currentRx;
        lastTxBytes = currentTx;
    }

    private void processCells(List<CellInfo> cellInfoList, JSONObject json, long now) throws JSONException {
        org.json.JSONArray cellsArray = new org.json.JSONArray();

        for (CellInfo cell : cellInfoList) {
            JSONObject cellData = new JSONObject();
            cellData.put("is_registered", cell.isRegistered());
            cellData.put("timestamp", now);

            // Zmienne pomocnicze dla AI
            float aiRsrp = -140.0f;
            float aiRsrq = -20.0f;
            float aiSinr = -10.0f;
            boolean readyForAi = false;

            // --- LOGIKA 5G NR ---
            if (cell instanceof CellInfoNr) {
                CellInfoNr nr = (CellInfoNr) cell;
                CellSignalStrengthNr signal = (CellSignalStrengthNr) nr.getCellSignalStrength();
                cellData.put("type", "5G_NR");

                if (nr.getCellIdentity() instanceof CellIdentityNr) {
                    CellIdentityNr id = (CellIdentityNr) nr.getCellIdentity();
                    putSafe(cellData, "pci", id.getPci());
                    putSafe(cellData, "nci", (int) id.getNci());
                }
                putSafe(cellData, "rsrp", signal.getSsRsrp());
                putSafe(cellData, "rsrq", signal.getSsRsrq());
                putSafe(cellData, "sinr", signal.getSsSinr());

                if (cell.isRegistered() && isValid(signal.getSsRsrp())) {
                    aiRsrp = signal.getSsRsrp();
                    aiRsrq = signal.getSsRsrq();
                    aiSinr = isValid(signal.getSsSinr()) ? signal.getSsSinr() : -10.0f;
                    readyForAi = true;
                }
            }
            // --- LOGIKA LTE ---
            else if (cell instanceof CellInfoLte) {
                CellInfoLte lte = (CellInfoLte) cell;
                CellSignalStrengthLte signal = lte.getCellSignalStrength();
                cellData.put("type", "LTE");

                putSafe(cellData, "pci", lte.getCellIdentity().getPci());
                putSafe(cellData, "earfcn", lte.getCellIdentity().getEarfcn());
                putSafe(cellData, "rsrp", signal.getRsrp());
                putSafe(cellData, "rsrq", signal.getRsrq());
                putSafe(cellData, "rssnr", signal.getRssnr());
                putSafe(cellData, "cqi", signal.getCqi());
                putSafe(cellData, "timing_advance", signal.getTimingAdvance());

                // LTE jako kotwica dla NSA - używamy do AI
                if (cell.isRegistered() && isValid(signal.getRsrp())) {
                    aiRsrp = signal.getRsrp();
                    aiRsrq = signal.getRsrq();
                    aiSinr = isValid(signal.getRssnr()) ? signal.getRssnr() : 0.0f;
                    readyForAi = true;
                }
            }

            // --- WNIOSKOWANIE AI (EDGE INFERENCE) ---
            if (readyForAi && anomalyDetector != null) {
                runAiAnalysis(cellData, aiRsrp, aiRsrq, aiSinr);
            }

            if (cellData.has("type")) {
                cellsArray.put(cellData);
            }
        }
        json.put("cells", cellsArray);
    }

    private void runAiAnalysis(JSONObject cellData, float rsrp, float rsrq, float sinr) throws JSONException {
        float[] features = new float[]{rsrp, rsrq, sinr};

        synchronized (dataWindow) {
            dataWindow.add(features);
            if (dataWindow.size() > AI_WINDOW_SIZE) {
                dataWindow.removeFirst();
            }

            if (dataWindow.size() == AI_WINDOW_SIZE) {
                // Konwersja listy na macierz [10][3]
                float[][] windowArray = new float[AI_WINDOW_SIZE][3];
                for (int i = 0; i < AI_WINDOW_SIZE; i++) {
                    windowArray[i] = dataWindow.get(i);
                }

                // Wykonanie predykcji
                float anomalyScore = anomalyDetector.analyze(windowArray);

                cellData.put("ai_anomaly_score", anomalyScore);
                boolean isAnomaly = anomalyScore > AI_ANOMALY_THRESHOLD;
                cellData.put("ai_status", isAnomaly ? "ANOMALY" : "NORMAL");

                if (isAnomaly) {
                    Log.w(TAG, "!!! WYKRYTO ANOMALIĘ !!! Score: " + anomalyScore);
                }
            } else {
                cellData.put("ai_status", "BUFFERING");
            }
        }
    }

    // ============================================================================================
    // METODY POMOCNICZE (UTILS)
    // ============================================================================================

    private void writeToJsonFile(JSONObject json) throws IOException {
        if (fileOutputStream != null) {
            fileOutputStream.write((json.toString() + "\n").getBytes());
            // fileOutputStream.flush(); // Można odkomentować dla debugowania, ale częsty flush zużywa I/O
        }
    }

    private boolean hasPermissions() {
        return ActivityCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void putSafe(JSONObject json, String key, int value) throws JSONException {
        if (value == Integer.MAX_VALUE || value == 2147483647) {
            json.put(key, JSONObject.NULL);
        } else {
            json.put(key, value);
        }
    }

    private boolean isValid(int value) {
        return value != Integer.MAX_VALUE && value != 2147483647;
    }

    private String getRefinedNetworkType(int rawType, int overrideType) {
        if (rawType == TelephonyManager.NETWORK_TYPE_LTE) {
            if (overrideType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA) return "5G_NSA";
            if (overrideType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED) return "5G_MMWAVE";
        }
        if (rawType == TelephonyManager.NETWORK_TYPE_NR) return "5G_SA";
        return "OTHER";
    }

    private Notification buildNotification() {
        Intent stopIntent = new Intent(this, NetworkCollectionService.class);
        stopIntent.setAction("STOP");
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Monitoring 5G/AI")
                .setContentText("Zbieranie danych i analiza anomalii w tle...")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Zatrzymaj", stopPendingIntent)
                .build();
    }

    // Klasa wewnętrzna do Callbacków (wymagana przez API)
    private class MyTelephonyCallback extends TelephonyCallback implements
            TelephonyCallback.DisplayInfoListener {
        @Override
        public void onDisplayInfoChanged(@NonNull TelephonyDisplayInfo displayInfo) {
            currentDisplayNetworkType = displayInfo.getOverrideNetworkType();
        }
    }
}