package com.jargo.neuralvpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import androidx.core.app.NotificationCompat;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import core.Core;

public class NeuralVpnService extends VpnService {

    public static final String ACTION_START = "com.jargo.neuralvpn.ACTION_START";
    public static final String ACTION_STOP = "com.jargo.neuralvpn.ACTION_STOP";
    public static final String EXTRA_SOCKS_HOST = "EXTRA_SOCKS_HOST";
    public static final String EXTRA_SOCKS_PORT = "EXTRA_SOCKS_PORT";
    public static final String EXTRA_DNS_ADDR = "EXTRA_DNS_ADDR";

    private static final String CHANNEL_ID = "neural_vpn_channel";
    private static final int NOTIFICATION_ID = 9001;

    private ParcelFileDescriptor vpnInterface = null;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

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
            String socksHost = intent.getStringExtra(EXTRA_SOCKS_HOST);
            int socksPort = intent.getIntExtra(EXTRA_SOCKS_PORT, 1080);
            String dnsAddr = intent.getStringExtra(EXTRA_DNS_ADDR);

            if (socksHost == null || socksHost.isEmpty()) {
                socksHost = "127.0.0.1";
            }
            if (dnsAddr == null || dnsAddr.isEmpty()) {
                dnsAddr = "1.1.1.1";
            }

            final String fullSocks = socksHost + ":" + socksPort;
            final String finalDns = dnsAddr;

            startForegroundNotification();

            executor.execute(() -> launchEngine(fullSocks, finalDns));
        }

        return START_STICKY;
    }

    private synchronized void launchEngine(String socksAddr, String dnsAddr) {
        if (vpnInterface != null) {
            Core.addLog("WARN", "Tunnel interface already established.");
            return;
        }

        try {
            Builder builder = new Builder();
            builder.setSession("NeuralVPN")
                    .setMtu(1500)
                    .addAddress("10.10.0.2", 24)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer(dnsAddr)
                    .setBlocking(true);

            vpnInterface = builder.establish();
            if (vpnInterface == null) {
                Core.addLog("ERROR", "Failed to establish Android VPN interface.");
                stopSelf();
                return;
            }

            int fd = vpnInterface.detachFd();
            Core.addLog("INFO", "Native TUN acquired. Handoff to Go Engine on FD: " + fd);

            boolean success = Core.startEngine(fd, socksAddr, dnsAddr);
            if (!success) {
                Core.addLog("ERROR", "Go Kernel rejected start parameters.");
                shutdownVpn();
            }

        } catch (Exception e) {
            Core.addLog("ERROR", "Fatal exception in VpnService setup: " + e.getMessage());
            shutdownVpn();
        }
    }

    private void startForegroundNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Neural VPN Active Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Monitors the active GoMobile VPN kernel session");
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
                .setContentTitle("Neural VPN Monolith Active")
                .setContentText("Routing network traffic through SOCKS5 micro-kernel")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(activityPendingIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", stopPendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        startForeground(NOTIFICATION_ID, notification);
    }

    private synchronized void shutdownVpn() {
        Core.addLog("INFO", "Shutting down VpnService and closing native handles.");
        Core.stopEngine();

        if (vpnInterface != null) {
            try {
                vpnInterface.close();
            } catch (IOException ignored) {
            }
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
        Core.addLog("WARN", "VPN credentials revoked by OS.");
        shutdownVpn();
        super.onRevoke();
    }
}
