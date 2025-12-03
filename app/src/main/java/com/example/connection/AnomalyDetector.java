package com.example.connection;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import org.tensorflow.lite.Interpreter;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

public class AnomalyDetector {

    private Interpreter interpreter;
    private static final String MODEL_FILE = "model_5g_dense_autoencoder.tflite";

    // Stałe do normalizacji (muszą pasować do tego, co robił MinMaxScaler w Pythonie)
    // Przyjmujemy typowe zakresy dla 5G:
    private static final float MIN_RSRP = -140.0f;
    private static final float MAX_RSRP = -40.0f;

    private static final float MIN_RSRQ = -30.0f;
    private static final float MAX_RSRQ = -3.0f;

    private static final float MIN_SNR = -10.0f;
    private static final float MAX_SNR = 30.0f;

    public AnomalyDetector(Context context) throws IOException {
        interpreter = new Interpreter(loadModelFile(context));
    }

    // Ładowanie modelu z folderu assets
    private MappedByteBuffer loadModelFile(Context context) throws IOException {
        AssetFileDescriptor fileDescriptor = context.getAssets().openFd(MODEL_FILE);
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    /**
     * Główna metoda analizy.
     * @param rawData Tablica 10x3 (10 ostatnich próbek: [RSRP, RSRQ, SNR])
     * @return Wynik anomalii (MSE - Błąd rekonstrukcji). Im wyższy, tym gorzej.
     */
    public float analyze(float[][] rawData) {
        // 1. Przygotowanie wejścia: [Batch=1, Time=10, Features=3]
        float[][][] input = new float[1][10][3];

        // Normalizacja danych (tak jak w Pythonie!)
        for (int i = 0; i < 10; i++) {
            input[0][i][0] = normalize(rawData[i][0], MIN_RSRP, MAX_RSRP);
            input[0][i][1] = normalize(rawData[i][1], MIN_RSRQ, MAX_RSRQ);
            input[0][i][2] = normalize(rawData[i][2], MIN_SNR, MAX_SNR);
        }

        // 2. Przygotowanie wyjścia
        float[][][] output = new float[1][10][3];

        // 3. Inferencja (Uruchomienie modelu)
        interpreter.run(input, output);

        // 4. Obliczenie błędu (MSE - Mean Squared Error)
        float mse = 0.0f;
        for (int i = 0; i < 10; i++) {
            for (int j = 0; j < 3; j++) {
                float diff = input[0][i][j] - output[0][i][j];
                mse += diff * diff;
            }
        }
        return mse / (10 * 3); // Średni błąd
    }

    private float normalize(float value, float min, float max) {
        float normalized = (value - min) / (max - min);
        // Ograniczamy do 0-1 (clip), żeby nie wyjść poza zakres
        return Math.max(0.0f, Math.min(1.0f, normalized));
    }

    public void close() {
        if (interpreter != null) interpreter.close();
    }
}