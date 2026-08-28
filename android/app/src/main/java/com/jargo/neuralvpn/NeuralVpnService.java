package com.jargo.neuralvpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import core.Core;

public class NeuralVpnService extends VpnService {
    private ParcelFileDescriptor vpnInterface = null;
    private static final String CHANNEL_ID = "NEURAL_VPN_CHANNEL";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Neural VPN [AKTIF]")
                .setContentText("KUL Engine GoMobile terhubung ke Kernel.")
                .setSmallIcon(android.R.drawable.ic_secure)
                .build();
        startForeground(1, notification);

        Builder builder = new Builder();
        builder.setMtu(1500);
        builder.addAddress("10.0.0.2", 24);
        builder.addRoute("0.0.0.0", 0); // Ambil semua lalu lintas
        builder.addDnsServer("10.0.0.3"); // Arahkan DNS ke Pipa
        
        try {
            vpnInterface = builder.establish();
            if (vpnInterface != null) {
                int fd = vpnInterface.getFd();
                // ⚡ Java menyerahkan File Descriptor ke Golang!
                new Thread(() -> Core.startEngine(fd)).start();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return START_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Neural VPN", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }
}
