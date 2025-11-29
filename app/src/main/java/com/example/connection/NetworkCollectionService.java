package com.example.connection;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.telephony.CellInfo;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoNr;
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
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

public class NetworkCollectionService extends Service {

    private static final String CHANNEL_ID = "NetworkMonitorChannel";
    private static final int NOTIFICATION_ID = 1;
    private static final String TAG = "NetworkService";

    private TelephonyManager telephonyManager;
    private TelephonyCallback telephonyCallback;
    private HandlerThread workerThread;
    private Handler workerHandler;
    private FileOutputStream fileOutputStream;
    private boolean isLogging = false;

    // Zmienna przechowująca aktualny "wyświetlany" typ sieci (do detekcji 5G NSA)
    private int currentDisplayNetworkType = TelephonyManager.NETWORK_TYPE_UNKNOWN;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        // 1. Uruchomienie wątku tła (nie obciąża UI)
        workerThread = new HandlerThread("NetworkCollectorWorker");
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());

        telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        setupLogFile();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        // 2. Start Foreground Service (wymagane, by system nie ubił procesu)
        startForeground(NOTIFICATION_ID, buildNotification());

        if (!isLogging) {
            registerCallbacks();
            isLogging = true;
        }

        return START_STICKY;
    }

    private void registerCallbacks() {
        // 1. Sprawdzenie uprawnień
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Brak uprawnień do rejestracji callbacka!");
            // Opcjonalnie: zatrzymaj serwis, jeśli brak uprawnień krytycznych
            // stopSelf();
            return;
        }

        // 2. Inicjalizacja Callbacka (ubezpieczenie przed nullem)
        if (telephonyCallback == null) {
            telephonyCallback = new MyTelephonyCallback();
        }

        // 3. Utworzenie Executora (Naprawa błędu "getThreadExecutor")
        // Executor to "pośrednik", który przekaże zadania do naszego workerHandlera (wątku tła)
        Executor serviceExecutor = new Executor() {
            @Override
            public void execute(Runnable command) {
                if (workerHandler != null) {
                    workerHandler.post(command);
                } else {
                    Log.e(TAG, "Błąd: workerHandler jest null!");
                }
            }
        };

        // 4. Rejestracja w systemie
        try {
            // Tutaj następuje kluczowe wywołanie - oba argumenty są teraz na pewno zainicjalizowane
            telephonyManager.registerTelephonyCallback(serviceExecutor, telephonyCallback);
            Log.d(TAG, "TelephonyCallback zarejestrowany pomyślnie.");
        } catch (Exception e) {
            Log.e(TAG, "Krytyczny błąd podczas rejestracji callbacka: ", e);
        }
    }

    // Nowoczesna implementacja Callbacków
    private class MyTelephonyCallback extends TelephonyCallback implements
            TelephonyCallback.ServiceStateListener,
            TelephonyCallback.SignalStrengthsListener,
            TelephonyCallback.DisplayInfoListener { // Kluczowe dla 5G NSA

        @Override
        public void onServiceStateChanged(@NonNull android.telephony.ServiceState serviceState) {
            workerHandler.post(() -> collectAndSaveData("ServiceState"));
        }

        @Override
        public void onSignalStrengthsChanged(@NonNull android.telephony.SignalStrength signalStrength) {
            workerHandler.post(() -> collectAndSaveData("SignalStrength"));
        }

        @Override
        public void onDisplayInfoChanged(@NonNull TelephonyDisplayInfo displayInfo) {
            // Tutaj wykrywamy, czy LTE to tak naprawdę 5G NSA
            currentDisplayNetworkType = displayInfo.getOverrideNetworkType();
            workerHandler.post(() -> collectAndSaveData("DisplayInfo"));
        }
    }

    private void collectAndSaveData(String trigger) {
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return;

        try {
            JSONObject json = new JSONObject();

            // A. TIMESTAMP DLA ML (Unix Epoch - łatwiejsze do synchronizacji)
            long now = System.currentTimeMillis();
            json.put("timestamp_epoch", now);
            json.put("timestamp_human", new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(new Date(now)));
            json.put("trigger", trigger);

            // B. POPRAWNA DETEKCJA SIECI (5G NSA vs LTE)
            int rawNetworkType = telephonyManager.getDataNetworkType();
            String refinedNetworkType = getRefinedNetworkType(rawNetworkType, currentDisplayNetworkType);
            json.put("network_type_raw", rawNetworkType);
            json.put("network_type_refined", refinedNetworkType);
            json.put("is_5g_nsa", currentDisplayNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA);

            // C. MONITOROWANIE BATERII (Dla optymalizacji Green AI)
            BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
            int batteryLevel = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            // Temperatura w stopniach Celsjusza (niektóre telefony zwracają int * 10)
            // Uwaga: Dokładne API temperatury zależy od producenta, tu używamy standardowego Intenta
            Intent batteryIntent = registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            assert batteryIntent != null;
            int tempRaw = batteryIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
            float tempC = tempRaw / 10.0f;

            json.put("battery_level", batteryLevel);
            json.put("battery_temp_c", tempC);
            json.put("is_charging", bm.isCharging());

            // D. SYGNAŁ (Z obsługą błędnych wartości i pętlą po wszystkich technologiach)
            if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                List<CellInfo> cellInfoList = telephonyManager.getAllCellInfo();
                if (cellInfoList != null) {
                    org.json.JSONArray cellsArray = new org.json.JSONArray();

                    for (CellInfo cell : cellInfoList) {
                        JSONObject cellData = new JSONObject();
                        cellData.put("is_registered", cell.isRegistered());
                        // Zapisujemy timestamp dla każdej komórki, ułatwi to parsowanie pojedynczych linii w Pythonie
                        cellData.put("timestamp", now);

                        if (cell instanceof CellInfoNr) {
                            CellInfoNr nr = (CellInfoNr) cell;
                            CellSignalStrengthNr signal = (CellSignalStrengthNr) nr.getCellSignalStrength();

                            cellData.put("type", "5G_NR");
                            // Identyfikacja
                            if (nr.getCellIdentity() instanceof android.telephony.CellIdentityNr) {
                                android.telephony.CellIdentityNr id = (android.telephony.CellIdentityNr) nr.getCellIdentity();
                                putCleanInt(cellData, "pci", id.getPci());
                                putCleanInt(cellData, "tac", id.getTac());
                                putCleanInt(cellData, "nci", (int) id.getNci()); // Cast long to int safe here for JSON
                            }

                            // Sygnał (SS - Synchronization Signal)
                            putCleanInt(cellData, "ss_rsrp", signal.getSsRsrp());
                            putCleanInt(cellData, "ss_rsrq", signal.getSsRsrq());
                            putCleanInt(cellData, "ss_sinr", signal.getSsSinr());

                            // CSI (Channel State Information) - ważne dla jakości pasma
                            putCleanInt(cellData, "csi_rsrp", signal.getCsiRsrp());
                            putCleanInt(cellData, "csi_rsrq", signal.getCsiRsrq());
                            putCleanInt(cellData, "csi_sinr", signal.getCsiSinr());

                        } else if (cell instanceof CellInfoLte) {
                            CellInfoLte lte = (CellInfoLte) cell;
                            android.telephony.CellSignalStrengthLte signal = lte.getCellSignalStrength();

                            cellData.put("type", "LTE");
                            putCleanInt(cellData, "pci", lte.getCellIdentity().getPci());
                            putCleanInt(cellData, "ci", lte.getCellIdentity().getCi());
                            putCleanInt(cellData, "earfcn", lte.getCellIdentity().getEarfcn());

                            putCleanInt(cellData, "rsrp", signal.getRsrp());
                            putCleanInt(cellData, "rsrq", signal.getRsrq());
                            putCleanInt(cellData, "rssnr", signal.getRssnr());
                            putCleanInt(cellData, "cqi", signal.getCqi());
                            putCleanInt(cellData, "timing_advance", signal.getTimingAdvance());


                        } else if (cell instanceof android.telephony.CellInfoGsm) {
                            android.telephony.CellInfoGsm gsm = (android.telephony.CellInfoGsm) cell;
                            android.telephony.CellSignalStrengthGsm signal = gsm.getCellSignalStrength();

                            cellData.put("type", "GSM");
                            putCleanInt(cellData, "cid", gsm.getCellIdentity().getCid());
                            putCleanInt(cellData, "lac", gsm.getCellIdentity().getLac());
                            putCleanInt(cellData, "dbm", signal.getDbm());
                            putCleanInt(cellData, "ber", signal.getBitErrorRate()); // Bit Error Rate - ważne dla anomalii!

                        } else if (cell instanceof android.telephony.CellInfoWcdma) {
                            android.telephony.CellInfoWcdma wcdma = (android.telephony.CellInfoWcdma) cell;
                            android.telephony.CellSignalStrengthWcdma signal = wcdma.getCellSignalStrength();

                            cellData.put("type", "WCDMA");
                            putCleanInt(cellData, "cid", wcdma.getCellIdentity().getCid());
                            putCleanInt(cellData, "lac", wcdma.getCellIdentity().getLac());
                            putCleanInt(cellData, "dbm", signal.getDbm());
                            putCleanInt(cellData, "ecno", signal.getEcNo());
                        }

                        // Dodajemy komórkę do listy, tylko jeśli udało się zebrać jakieś dane (np. typ nie jest pusty)
                        if (cellData.has("type")) {
                            cellsArray.put(cellData);
                        }
                    }
                    // Dodajemy całą tablicę komórek do głównego obiektu JSON
                    json.put("cells", cellsArray);
                }
            }

            // Zapis do pliku (Format JSON Lines - jedna linia = jeden rekord)
            String logLine = json.toString() + "\n";
            if (fileOutputStream != null) {
                fileOutputStream.write(logLine.getBytes());
            }

        } catch (Exception e) {
            Log.e(TAG, "Error collecting data", e);
        }
    }

    // Logika rozpoznawania 5G NSA
    private String getRefinedNetworkType(int rawType, int overrideType) {
        if (rawType == TelephonyManager.NETWORK_TYPE_LTE) {
            if (overrideType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA) {
                return "5G_NSA"; // To jest to, co widziałeś jako LTE!
            }
            if (overrideType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED) {
                return "5G_MMWAVE_OR_ADVANCED";
            }
        }
        if (rawType == TelephonyManager.NETWORK_TYPE_NR) {
            return "5G_SA"; // Standalone
        }
        return "OTHER";
    }

    private void setupLogFile() {
        try {
            File dir = getExternalFilesDir(null);
            String filename = "data_ml_ready_" + System.currentTimeMillis() + ".jsonl";
            File file = new File(dir, filename);
            fileOutputStream = new FileOutputStream(file, true);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private Notification buildNotification() {
        Intent stopIntent = new Intent(this, NetworkCollectionService.class);
        stopIntent.setAction("STOP");
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Zbieranie danych 5G")
                .setContentText("Monitorowanie parametrów radiowych i baterii...")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Zatrzymaj", stopPendingIntent)
                .build();
    }

    private void createNotificationChannel() {
        NotificationChannel serviceChannel = new NotificationChannel(
                CHANNEL_ID,
                "Network Monitor Channel",
                NotificationManager.IMPORTANCE_LOW
        );
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(serviceChannel);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            if (telephonyCallback != null) telephonyManager.unregisterTelephonyCallback(telephonyCallback);
            if (workerThread != null) workerThread.quitSafely();
            if (fileOutputStream != null) fileOutputStream.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }



    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // Metoda pomocnicza do czyszczenia danych pod ML
    // Jeśli wartość to Integer.MAX_VALUE (brak danych w Android API), wstawiamy NULL
    private void putCleanInt(JSONObject json, String key, int value) {
        try {
            if (value == Integer.MAX_VALUE || value == 2147483647) {
                json.put(key, JSONObject.NULL);
            } else {
                json.put(key, value);
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }
}