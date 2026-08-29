package com.jargo.neuralvpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import androidx.core.app.NotificationCompat;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import core.Core;
import core.SocketProtector;

public class NeuralVpnService extends VpnService implements SocketProtector {

    public static final String ACTION_START = "com.jargo.neuralvpn.ACTION_START";
    public static final String ACTION_STOP = "com.jargo.neuralvpn.ACTION_STOP";
    public static final String EXTRA_SOCKS_ADDR = "EXTRA_SOCKS_ADDR";
    public static final String EXTRA_DNS_ADDR = "EXTRA_DNS_ADDR";

    private static final String CHANNEL_ID = "neural_monolith_channel";
    private static final int NOTIFICATION_ID = 9001;

    private ParcelFileDescriptor vpnInterface = null;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    public void onCreate() {
        super.onCreate();
        Core.registerSocketProtector(this);
    }

    @Override
    public boolean protect(long fd) {
        return protect((int) fd);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            shutdownVpn();
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(action)) {
            String socksAddr = intent.getStringExtra(EXTRA_SOCKS_ADDR);
            String dnsAddr = intent.getStringExtra(EXTRA_DNS_ADDR);

            if (socksAddr == null || socksAddr.isEmpty()) socksAddr = "127.0.0.3:2007";
            if (dnsAddr == null || dnsAddr.isEmpty()) dnsAddr = "1.1.1.1";

            final String finalSocks = socksAddr;
            final String finalDns = dnsAddr;

            startForegroundNotification();
            executor.execute(() -> launchEngine(finalSocks, finalDns));
        }

        return START_STICKY;
    }

    private synchronized void launchEngine(String socksAddr, String dnsAddr) {
        if (vpnInterface != null) return;

        try {
            Builder builder = new Builder();
            builder.setSession("NeuralVPN Monolith")
                    .setMtu(1500)
                    .addAddress("10.10.0.2", 24)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("10.10.0.2")
                    .setBlocking(true);

            // Bypass paket APK kita sendiri
            try {
                builder.addDisallowedApplication(getPackageName());
            } catch (PackageManager.NameNotFoundException ignored) {}

            vpnInterface = builder.establish();
            if (vpnInterface == null) {
                Core.addLog("ERROR", "Failed to establish VPN interface.");
                stopSelf();
                return;
            }

            int fd = vpnInterface.detachFd();
            Core.addLog("INFO", "TUN Interface established. Launching Monolith Kernel...");

            boolean success = Core.startEngine(fd, socksAddr, dnsAddr);
            if (!success) {
                Core.addLog("ERROR", "Monolith Kernel start rejected.");
                shutdownVpn();
            }

        } catch (Exception e) {
            Core.addLog("ERROR", "Engine Error: " + e.getMessage());
            shutdownVpn();
        }
    }

    private void startForegroundNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Neural VPN Monolith Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            manager.createNotificationChannel(channel);
        }

        Intent stopIntent = new Intent(this, NeuralVpnService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
        );

        Intent activityIntent = new Intent(this, MainActivity.class);
        PendingIntent activityPendingIntent = PendingIntent.getActivity(
                this, 0, activityIntent, PendingIntent.FLAG_IMMUTABLE
        );

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Neural Monolith Active")
                .setContentText("All traffic -> Go Kernel -> Global Internet")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(activityPendingIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", stopPendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        startForeground(NOTIFICATION_ID, notification);
    }

    private synchronized void shutdownVpn() {
        Core.stopEngine();

        if (vpnInterface != null) {
            try {
                vpnInterface.close();
            } catch (IOException ignored) {}
            vpnInterface = null;
        }

        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        shutdownVpn();
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onRevoke() {
        shutdownVpn();
        super.onRevoke();
    }
}
