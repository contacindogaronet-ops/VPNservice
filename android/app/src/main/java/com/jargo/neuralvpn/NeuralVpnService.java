package com.jargo.neuralvpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import android.util.Log;

public class NeuralVpnService extends VpnService {
    private static final String CHANNEL_ID = "NEURAL_VPN_CHANNEL";
    private Process engineProcess;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Neural VPN [AKTIF]")
                .setContentText("Daemon AI berjalan di 127.0.0.3:2007")
                .setSmallIcon(android.R.drawable.ic_secure)
                .build();
        startForeground(1, notification);

        // ⚡ EKSEKUSI DAEMON
        extractAndRunEngine();

        // Rute VPN diarahkan ke IP Mesin Golang Anda
        Builder builder = new Builder();
        builder.setMtu(1500);
        builder.addAddress("10.0.0.2", 24);
        builder.addRoute("0.0.0.0", 0);
        builder.addDnsServer("127.0.0.3"); // 🔴 Arahkan DNS ke Daemon AI lokal
        
        try {
            builder.establish();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return START_STICKY;
    }

    private void extractAndRunEngine() {
        new Thread(() -> {
            try {
                File engineFile = new File(getFilesDir(), "core_engine");
                
                // Ekstrak biner dari APK ke internal storage jika belum ada
                if (!engineFile.exists()) {
                    InputStream in = getAssets().open("core_engine");
                    FileOutputStream out = new FileOutputStream(engineFile);
                    byte[] buffer = new byte[1024];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                    in.close();
                    out.flush();
                    out.close();
                }

                // Beri izin eksekusi (chmod 700)
                engineFile.setExecutable(true, true);

                // Jalankan Daemon Golang!
                Log.i("JARGO", "Memulai Daemon Golang Independen...");
                ProcessBuilder pb = new ProcessBuilder(engineFile.getAbsolutePath());
                engineProcess = pb.start();

            } catch (Exception e) {
                Log.e("JARGO", "Gagal menghidupkan mesin: " + e.getMessage());
            }
        }).start();
    }

    @Override
    public void onDestroy() {
        if (engineProcess != null) {
            engineProcess.destroy(); // Bunuh daemon jika VPN dimatikan
        }
        super.onDestroy();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Neural VPN Status", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }
}
