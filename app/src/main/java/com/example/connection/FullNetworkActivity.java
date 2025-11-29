package com.example.connection;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.telephony.CellIdentityNr;
import android.telephony.CellInfo;
import android.telephony.CellInfoCdma;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoNr;
import android.telephony.CellInfoTdscdma;
import android.telephony.CellInfoWcdma;
import android.telephony.CellSignalStrength;
import android.telephony.CellSignalStrengthCdma;
import android.telephony.CellSignalStrengthGsm;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellSignalStrengthNr;
import android.telephony.CellSignalStrengthTdscdma;
import android.telephony.CellSignalStrengthWcdma;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyDisplayInfo;
import android.telephony.TelephonyManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.CarrierConfigManager;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
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
import java.util.ArrayList;

public class FullNetworkActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;
    private TelephonyManager telephonyManager;
    private SubscriptionManager subscriptionManager;
    private CarrierConfigManager carrierConfigManager;
    private TextView dataTextView;
    private final List<JSONObject> collectedData = new ArrayList<>();
    private boolean isCollectingData = false;
    private TelephonyCallback telephonyCallback;

    // Lista wymaganych uprawnień
    private static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PRECISE_PHONE_STATE,
            Manifest.permission.READ_BASIC_PHONE_STATE,
            Manifest.permission.READ_PHONE_NUMBERS,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        dataTextView = findViewById(R.id.dataTextView);
        Button startButton = findViewById(R.id.startButton);
        Button stopButton = findViewById(R.id.stopButton);
        Button exportButton = findViewById(R.id.exportButton);

        telephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        subscriptionManager = (SubscriptionManager) getSystemService(TELEPHONY_SUBSCRIPTION_SERVICE);
        carrierConfigManager = (CarrierConfigManager) getSystemService(CARRIER_CONFIG_SERVICE);

        startButton.setOnClickListener(v -> startDataCollection());
        stopButton.setOnClickListener(v -> stopDataCollection());
        exportButton.setOnClickListener(v -> exportDataToJson());

        checkPermissions();
    }

    private void checkPermissions() {
        boolean allGranted = true;
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (!allGranted) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int grantResult : grantResults) {
                if (grantResult != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (!allGranted) {
                Toast.makeText(this, "Niektóre uprawnienia nie zostały przyznane. Niektóre funkcje mogą nie działać.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void startDataCollection() {
        if (isCollectingData) return;

        isCollectingData = true;
        collectedData.clear();

        // Rejestracja słuchacza zmian
        registerTelephonyCallbacks();

        // Pierwsze pobranie danych
        runOnUiThread(() -> {
            JSONObject networkData = collectNetworkData();
            collectedData.add(networkData);
            displayData(networkData);
        });

        Toast.makeText(this, "Rozpoczęto zbieranie danych", Toast.LENGTH_SHORT).show();
    }

    private void stopDataCollection() {
        if (!isCollectingData) return;

        isCollectingData = false;
        if (telephonyCallback != null) {
            telephonyManager.unregisterTelephonyCallback(telephonyCallback);
        }

        Toast.makeText(this, "Zatrzymano zbieranie danych", Toast.LENGTH_SHORT).show();
    }

    private void registerTelephonyCallbacks() {
        if (telephonyManager == null) {
            Toast.makeText(this, "TelephonyManager not available", Toast.LENGTH_SHORT).show();
            return;
        }

        Executor executor = this.getMainExecutor();

        // Create a single TelephonyCallback that implements all required interfaces
        class MyTelephonyCallback extends TelephonyCallback implements
                TelephonyCallback.ServiceStateListener,
                TelephonyCallback.SignalStrengthsListener,
                TelephonyCallback.DisplayInfoListener {

            @Override
            public void onServiceStateChanged(@NonNull ServiceState serviceState) {
                if (isCollectingData) {
                    handleDataCollection("ServiceState changed");
                }
            }

            @Override
            public void onSignalStrengthsChanged(@NonNull SignalStrength signalStrength) {
                if (isCollectingData) {
                    handleDataCollection("SignalStrength changed");
                }
            }

            @Override
            public void onDisplayInfoChanged(@NonNull TelephonyDisplayInfo displayInfo) {
                if (isCollectingData) {
                    handleDataCollection("DisplayInfo changed");
                }
            }
        }

        telephonyCallback = new MyTelephonyCallback();

        try {
            // Register the single callback that implements all interfaces
            telephonyManager.registerTelephonyCallback(executor, telephonyCallback);
            Toast.makeText(this, "Telephony callbacks registered successfully", Toast.LENGTH_SHORT).show();

        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Error registering telephony callbacks: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // Metoda pomocnicza do obsługi kolekcji danych
    private void handleDataCollection(String source) {
        runOnUiThread(() -> {
            try {
                JSONObject networkData = collectNetworkData();
                collectedData.add(networkData);
                displayData(networkData);

                // Opcjonalnie: logowanie źródła zmiany
                Log.d("NetworkData", "Data collected due to: " + source);

            } catch (Exception e) {
                Log.e("NetworkData", "Error during data collection: " + e.getMessage());
                Toast.makeText(this, "Error collecting data", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @SuppressLint("MissingPermission")
    private JSONObject collectNetworkData() {
        JSONObject data = new JSONObject();
        try {
            if (telephonyManager == null) {
                data.put("error", "TelephonyManager not available");
                return data;
            }

            // Sprawdzaj uprawnienia przed każdym wywołaniem
            if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                data.put("error", "Missing required permissions");
                return data;
            }

            // Timestamp
            data.put("timestamp", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()));

            // Podstawowe informacje o sieci
            String networkOperator = telephonyManager.getNetworkOperatorName();
            data.put("network_operator", networkOperator != null ? networkOperator : "Unknown");
            data.put("network_type", getNetworkTypeName(telephonyManager.getDataNetworkType()));
            data.put("is_5g", telephonyManager.getDataNetworkType() == TelephonyManager.NETWORK_TYPE_NR);
            data.put("network_country_iso", telephonyManager.getNetworkCountryIso());

            // Bezpieczne pobieranie IMEI
            if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                try {
                    data.put("imei", telephonyManager.getImei());
                } catch (SecurityException e) {
                    data.put("imei", "Permission denied");
                }
            } else {
                data.put("imei", "No permission");
            }

            // Stan usług (ServiceState)
            ServiceState serviceState = telephonyManager.getServiceState();
            if (serviceState != null) {
                JSONObject serviceStateData = new JSONObject();
                serviceStateData.put("voice_reg_state", serviceState.getState());
                serviceStateData.put("roaming", serviceState.getRoaming());
                serviceStateData.put("emergency_only", serviceState.getState() == ServiceState.STATE_EMERGENCY_ONLY);
                serviceStateData.put("voice_capable", serviceState.isUsingNonTerrestrialNetwork());
                data.put("service_state", serviceStateData);
            }

            // Informacje o karcie SIM
            data.put("sim_operator", telephonyManager.getSimOperatorName());
            data.put("sim_operator_id", telephonyManager.getSimOperator());

            // Informacje o sygnale
            SignalStrength signalStrength = telephonyManager.getSignalStrength();
            if (signalStrength != null) {
                JSONObject signalData = new JSONObject();
                for (CellSignalStrength css : signalStrength.getCellSignalStrengths()) {
                    if (css instanceof CellSignalStrengthNr) {
                        CellSignalStrengthNr nr = (CellSignalStrengthNr) css;
                        signalData.put("nr_dbm", nr.getDbm());
                        signalData.put("nr_cqi", nr.getCsiCqiTableIndex());
                        signalData.put("nr_ss_rsrp", nr.getSsRsrp());
                        signalData.put("nr_ss_rsrq", nr.getSsRsrq());
                        signalData.put("nr_ss_sinr", nr.getSsSinr());
                    } else if (css instanceof CellSignalStrengthLte) {
                        CellSignalStrengthLte lte = (CellSignalStrengthLte) css;
                        signalData.put("lte_dbm", lte.getDbm());
                        signalData.put("lte_rsrp", lte.getRsrp());
                        signalData.put("lte_rsrq", lte.getRsrq());
                        signalData.put("lte_rssnr", lte.getRssnr());
                        signalData.put("lte_cqi", lte.getCqi());
                    } else if (css instanceof CellSignalStrengthGsm) {
                        CellSignalStrengthGsm gsm = (CellSignalStrengthGsm) css;
                        signalData.put("gsm_dbm", gsm.getDbm());
                        signalData.put("gsm_asu", gsm.getAsuLevel());
                        signalData.put("gsm_ber", gsm.getBitErrorRate());
                    } else if (css instanceof CellSignalStrengthWcdma) {
                        CellSignalStrengthWcdma wcdma = (CellSignalStrengthWcdma) css;
                        signalData.put("wcdma_dbm", wcdma.getDbm());
                        signalData.put("wcdma_ecio", wcdma.getEcNo());
                    } else if (css instanceof CellSignalStrengthTdscdma) {
                        CellSignalStrengthTdscdma tdscdma = (CellSignalStrengthTdscdma) css;
                        signalData.put("tdscdma_dbm", tdscdma.getDbm());
                        signalData.put("tdscdma_rscp", tdscdma.getRscp());
                    } else if (css instanceof CellSignalStrengthCdma) {
                        CellSignalStrengthCdma cdma = (CellSignalStrengthCdma) css;
                        signalData.put("cdma_dbm", cdma.getCdmaDbm());
                        signalData.put("cdma_ecio", cdma.getCdmaEcio());
                        signalData.put("evdo_dbm", cdma.getEvdoDbm());
                        signalData.put("evdo_ecio", cdma.getEvdoEcio());
                        signalData.put("evdo_snr", cdma.getEvdoSnr());
                    }
                }
                data.put("signal_strength", signalData);
            }

            // Informacje o komórkach (CellInfo)
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                List<CellInfo> cellInfoList = telephonyManager.getAllCellInfo();
                if (cellInfoList != null) {
                    JSONArray cellsArray = getJsonArray(cellInfoList);
                    data.put("cell_info", cellsArray);
                }
            }

            // Informacje o subskrypcjach i kartach SIM
            if (subscriptionManager != null && checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                List<SubscriptionInfo> subscriptionInfos = subscriptionManager.getActiveSubscriptionInfoList();
                if (subscriptionInfos != null) {
                    JSONArray simArray = new JSONArray();
                    for (SubscriptionInfo info : subscriptionInfos) {
                        JSONObject simData = new JSONObject();
                        simData.put("sim_slot", info.getSimSlotIndex());
                        simData.put("display_name", info.getDisplayName());

                        // Bezpieczne pobieranie numeru telefonu
                        try {
                            String phoneNumber = subscriptionManager.getPhoneNumber(info.getSubscriptionId());
                            simData.put("number", phoneNumber);
                        } catch (SecurityException e) {
                            simData.put("number", "Permission denied");
                        }

                        simData.put("carrier_name", info.getCarrierName());
                        simData.put("country_iso", info.getCountryIso());

                        // Bezpieczne pobieranie IMEI dla konkretnego slotu
                        try {
                            String slotImei = telephonyManager.getImei(info.getSimSlotIndex());
                            simData.put("imei", slotImei != null ? slotImei : "Unknown");
                        } catch (SecurityException e) {
                            simData.put("imei", "Permission denied");
                        }

                        simData.put("mcc", info.getMccString());
                        simData.put("mnc", info.getMncString());
                        simArray.put(simData);
                    }
                    data.put("sim_info", simArray);
                }
            }

            // Informacje o konfiguracji operatora (CarrierConfigManager)
            if (carrierConfigManager != null) {
                JSONObject carrierConfig = new JSONObject();
                PersistableBundle configBundle = carrierConfigManager.getConfig();
                if (configBundle != null) {
                    // Pobieranie przykładowych, istniejących kluczy konfiguracji
                    if (configBundle.containsKey(CarrierConfigManager.KEY_APN_EXPAND_BOOL)) {
                        carrierConfig.put("apn_expandable", configBundle.getBoolean(CarrierConfigManager.KEY_APN_EXPAND_BOOL));
                    }
                    if (configBundle.containsKey(CarrierConfigManager.KEY_CARRIER_VOLTE_AVAILABLE_BOOL)) {
                        carrierConfig.put("volte_available", configBundle.getBoolean(CarrierConfigManager.KEY_CARRIER_VOLTE_AVAILABLE_BOOL));
                    }
                    if (configBundle.containsKey(CarrierConfigManager.KEY_CARRIER_WFC_IMS_AVAILABLE_BOOL)) {
                        carrierConfig.put("wifi_calling_available", configBundle.getBoolean(CarrierConfigManager.KEY_CARRIER_WFC_IMS_AVAILABLE_BOOL));
                    }
                    if (configBundle.containsKey(CarrierConfigManager.ImsSms.KEY_SMS_OVER_IMS_SUPPORTED_BOOL)) {
                        carrierConfig.put("ims_sms_supported", configBundle.getBoolean(CarrierConfigManager.ImsSms.KEY_SMS_OVER_IMS_SUPPORTED_BOOL));
                    }
                    data.put("carrier_config", carrierConfig);
                }
            }
        } catch (JSONException e) {
            e.printStackTrace();
            try {
                data.put("error", "JSON exception: " + e.getMessage());
            } catch (JSONException ex) {
                ex.printStackTrace();
            }
        }
        return data;
    }

    private static @NonNull JSONArray getJsonArray(List<CellInfo> cellInfoList) throws JSONException {
        JSONArray cellsArray = new JSONArray();
        for (CellInfo cellInfo : cellInfoList) {
            JSONObject cellData = new JSONObject();

            if (cellInfo instanceof CellInfoNr) {
                CellInfoNr nr = (CellInfoNr) cellInfo;
                CellIdentityNr cellIdentityNr = (CellIdentityNr) nr.getCellIdentity();
                cellData.put("type", "5G_NR");
                cellData.put("mcc", cellIdentityNr.getMccString());
                cellData.put("mnc", cellIdentityNr.getMncString());
                cellData.put("plmn", cellIdentityNr.getOperatorAlphaLong());
                cellData.put("pci", cellIdentityNr.getPci());
                cellData.put("tac", cellIdentityNr.getTac());
                cellData.put("nci", cellIdentityNr.getNci());
                cellData.put("arfcn", cellIdentityNr.getNrarfcn());
                // Parametry sygnału NR
                CellSignalStrengthNr signal = (CellSignalStrengthNr) cellInfo.getCellSignalStrength();
                cellData.put("rsrp", signal.getDbm());
                cellData.put("rsrq", signal.getSsRsrq());
                cellData.put("ss_sinr", signal.getSsSinr());
                cellData.put("csi_rsrq", signal.getCsiRsrq());
                cellData.put("cqi", signal.getCsiCqiTableIndex());
            } else if (cellInfo instanceof CellInfoLte) {
                CellInfoLte lte = (CellInfoLte) cellInfo;
                cellData.put("type", "LTE");
                cellData.put("mcc", lte.getCellIdentity().getMccString());
                cellData.put("mnc", lte.getCellIdentity().getMncString());
                cellData.put("plmn", lte.getCellIdentity().getOperatorAlphaLong());
                cellData.put("pci", lte.getCellIdentity().getPci());
                cellData.put("tac", lte.getCellIdentity().getTac());
                cellData.put("ci", lte.getCellIdentity().getCi());
                cellData.put("earfcn", lte.getCellIdentity().getEarfcn());
                // Parametry sygnału LTE
                CellSignalStrengthLte signal = lte.getCellSignalStrength();
                cellData.put("rsrp", signal.getRsrp());
                cellData.put("rsrq", signal.getRsrq());
                cellData.put("rssnr", signal.getRssnr());
                cellData.put("cqi", signal.getCqi());
            } else if (cellInfo instanceof CellInfoGsm) {
                CellInfoGsm gsm = (CellInfoGsm) cellInfo;
                cellData.put("type", "GSM");
                cellData.put("mcc", gsm.getCellIdentity().getMccString());
                cellData.put("mnc", gsm.getCellIdentity().getMncString());
                cellData.put("plmn", gsm.getCellIdentity().getOperatorAlphaLong());
                cellData.put("lac", gsm.getCellIdentity().getLac());
                cellData.put("cid", gsm.getCellIdentity().getCid());
                cellData.put("arfcn", gsm.getCellIdentity().getArfcn());
                cellData.put("bsic", gsm.getCellIdentity().getBsic());
                // Parametry sygnału GSM
                CellSignalStrengthGsm signal = gsm.getCellSignalStrength();
                cellData.put("dbm", signal.getDbm());
                cellData.put("asu", signal.getAsuLevel());
                cellData.put("ber", signal.getBitErrorRate());
            } else if (cellInfo instanceof CellInfoWcdma) {
                CellInfoWcdma wcdma = (CellInfoWcdma) cellInfo;
                cellData.put("type", "WCDMA");
                cellData.put("mcc", wcdma.getCellIdentity().getMccString());
                cellData.put("mnc", wcdma.getCellIdentity().getMncString());
                cellData.put("plmn", wcdma.getCellIdentity().getOperatorAlphaLong());
                cellData.put("lac", wcdma.getCellIdentity().getLac());
                cellData.put("cid", wcdma.getCellIdentity().getCid());
                cellData.put("psc", wcdma.getCellIdentity().getPsc());
                cellData.put("uarfcn", wcdma.getCellIdentity().getUarfcn());
                // Parametry sygnału WCDMA
                CellSignalStrengthWcdma signal = wcdma.getCellSignalStrength();
                cellData.put("dbm", signal.getDbm());
                cellData.put("ecno", signal.getEcNo());
            } else if (cellInfo instanceof CellInfoTdscdma) {
                CellInfoTdscdma tdscdma = (CellInfoTdscdma) cellInfo;
                cellData.put("type", "TDSCDMA");
                cellData.put("mcc", tdscdma.getCellIdentity().getMccString());
                cellData.put("mnc", tdscdma.getCellIdentity().getMncString());
                cellData.put("plmn", tdscdma.getCellIdentity().getOperatorAlphaLong());
                cellData.put("lac", tdscdma.getCellIdentity().getLac());
                cellData.put("cid", tdscdma.getCellIdentity().getCid());
                cellData.put("uarfcn", tdscdma.getCellIdentity().getUarfcn());
                cellData.put("psc", tdscdma.getCellIdentity().getCpid());
                // Parametry sygnału TD-SCDMA
                CellSignalStrengthTdscdma signal = tdscdma.getCellSignalStrength();
                cellData.put("dbm", signal.getDbm());
                cellData.put("rscp", signal.getRscp());
            } else if (cellInfo instanceof CellInfoCdma) {
                CellInfoCdma cdma = (CellInfoCdma) cellInfo;
                cellData.put("type", "CDMA");
                cellData.put("bid", cdma.getCellIdentity().getBasestationId());
                cellData.put("sid", cdma.getCellIdentity().getSystemId());
                cellData.put("nid", cdma.getCellIdentity().getNetworkId());
                cellData.put("long", cdma.getCellIdentity().getLongitude());
                cellData.put("lat", cdma.getCellIdentity().getLatitude());
                // Parametry sygnału CDMA/EVDO
                CellSignalStrengthCdma signal = cdma.getCellSignalStrength();
                cellData.put("dbm", signal.getCdmaDbm());
                cellData.put("ecio", signal.getCdmaEcio());
                cellData.put("evdo_dbm", signal.getEvdoDbm());
                cellData.put("evdo_ecio", signal.getEvdoEcio());
                cellData.put("evdo_snr", signal.getEvdoSnr());
            }
            cellData.put("is_registered", cellInfo.isRegistered());
            cellsArray.put(cellData);
        }
        return cellsArray;
    }

    private String getNetworkTypeName(int networkType) {
        switch (networkType) {
            case TelephonyManager.NETWORK_TYPE_NR: return "5G_NR";
            case TelephonyManager.NETWORK_TYPE_LTE: return "LTE";
            case TelephonyManager.NETWORK_TYPE_HSPAP: return "HSPA+";
            case TelephonyManager.NETWORK_TYPE_HSDPA: return "HSDPA";
            case TelephonyManager.NETWORK_TYPE_HSUPA: return "HSUPA";
            case TelephonyManager.NETWORK_TYPE_HSPA: return "HSPA";
            case TelephonyManager.NETWORK_TYPE_EDGE: return "EDGE";
            case TelephonyManager.NETWORK_TYPE_GPRS: return "GPRS";
            case TelephonyManager.NETWORK_TYPE_CDMA: return "CDMA";
            case TelephonyManager.NETWORK_TYPE_1xRTT: return "1xRTT";
            case TelephonyManager.NETWORK_TYPE_EVDO_0: return "EVDO_0";
            case TelephonyManager.NETWORK_TYPE_EVDO_A: return "EVDO_A";
            case TelephonyManager.NETWORK_TYPE_EVDO_B: return "EVDO_B";
            case TelephonyManager.NETWORK_TYPE_UMTS: return "UMTS";
            case TelephonyManager.NETWORK_TYPE_IDEN: return "iDEN";
            case TelephonyManager.NETWORK_TYPE_GSM: return "GSM";
            case TelephonyManager.NETWORK_TYPE_TD_SCDMA: return "TD_SCDMA";
            case TelephonyManager.NETWORK_TYPE_IWLAN: return "IWLAN";
            default: return "Unknown";
        }
    }

    private void displayData(JSONObject data) {
        try {
            StringBuilder displayText = new StringBuilder();
            displayText.append("Operator: ").append(data.getString("network_operator")).append("\n");
            displayText.append("Typ sieci: ").append(data.getString("network_type")).append("\n");
            displayText.append("Kraj: ").append(data.getString("network_country_iso")).append("\n");
            displayText.append("5G: ").append(data.getBoolean("is_5g") ? "Tak" : "Nie").append("\n");
            displayText.append("IMEI: ").append(data.getString("imei")).append("\n");

            if (data.has("signal_strength")) {
                JSONObject signal = data.getJSONObject("signal_strength");
                if (signal.has("nr_dbm")) displayText.append("5G dBm: ").append(signal.getInt("nr_dbm")).append("\n");
                if (signal.has("lte_dbm")) displayText.append("LTE dBm: ").append(signal.getInt("lte_dbm")).append("\n");
                if (signal.has("gsm_dbm")) displayText.append("GSM dBm: ").append(signal.getInt("gsm_dbm")).append("\n");
                if (signal.has("wcdma_dbm")) displayText.append("WCDMA dBm: ").append(signal.getInt("wcdma_dbm")).append("\n");
                if (signal.has("tdscdma_dbm")) displayText.append("TDSCDMA dBm: ").append(signal.getInt("tdscdma_dbm")).append("\n");
                if (signal.has("cdma_dbm")) displayText.append("CDMA dBm: ").append(signal.getInt("cdma_dbm")).append("\n");
            }

            dataTextView.setText(displayText.toString());
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private void exportDataToJson() {
        if (collectedData.isEmpty()) {
            Toast.makeText(this, "Brak danych do eksportu", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            JSONArray dataArray = new JSONArray(collectedData);
            String fileName = "network_data_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".json";
            File file = new File(getExternalFilesDir(null), fileName);

            FileOutputStream fos = new FileOutputStream(file);
            fos.write(dataArray.toString(2).getBytes());
            fos.close();

            Toast.makeText(this, "Dane zapisane do: " + file.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (IOException | JSONException e) {
            e.printStackTrace();
            Toast.makeText(this, "Błąd podczas zapisywania danych", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopDataCollection();
    }
}