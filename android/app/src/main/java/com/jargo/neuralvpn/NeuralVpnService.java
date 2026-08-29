package com.jargo.neuralvpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import androidx.core.app.NotificationCompat;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import core.Core;
import core.SocketProtector;

public class NeuralVpnService extends VpnService implements SocketProtector {

    public static final String ACTION_START = "com.jargo.neuralvpn.ACTION_START";
    public static final String ACTION_STOP = "com.jargo.neuralvpn.ACTION_STOP";
    public static final String EXTRA_SOCKS_HOST = "EXTRA_SOCKS_HOST";
    public static final String EXTRA_SOCKS_PORT = "EXTRA_SOCKS_PORT";
    public static final String EXTRA_DNS_ADDR = "EXTRA_DNS_ADDR";
    public static final String EXTRA_USE_INTERNAL_DNS = "EXTRA_USE_INTERNAL_DNS";
    public static final String EXTRA_TARGET_PACKAGES = "EXTRA_TARGET_PACKAGES"; // Whitelist Mode

    private static final String CHANNEL_ID = "neural_vpn_channel";
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
            String socksHost = intent.getStringExtra(EXTRA_SOCKS_HOST);
            int socksPort = intent.getIntExtra(EXTRA_SOCKS_PORT, 2007);
            String dnsAddr = intent.getStringExtra(EXTRA_DNS_ADDR);
            boolean useInternalDns = intent.getBooleanExtra(EXTRA_USE_INTERNAL_DNS, true);
            String targetPackages = intent.getStringExtra(EXTRA_TARGET_PACKAGES);

            if (socksHost == null || socksHost.isEmpty()) {
                socksHost = "127.0.0.3";
            }
            if (dnsAddr == null || dnsAddr.isEmpty()) {
                dnsAddr = "10.10.0.2";
            }

            final String fullSocks = socksHost + ":" + socksPort;
            final String finalDns = dnsAddr;

            startForegroundNotification(useInternalDns);

            executor.execute(() -> launchEngine(fullSocks, finalDns, useInternalDns, targetPackages));
        }

        return START_STICKY;
    }

    private synchronized void launchEngine(String socksAddr, String dnsAddr, boolean useInternalDns, String targetPackages) {
        if (vpnInterface != null) {
            return;
        }

        try {
            Builder builder = new Builder();
            builder.setSession("NeuralVPN")
                    .setMtu(1500)
                    .addAddress("10.10.0.2", 24)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer(useInternalDns ? "10.10.0.2" : dnsAddr)
                    .setBlocking(true);

            // MODE 1: WHITELIST MODE (Hanya Proxy Aplikasi yang Dipilih - 100% Anti-Loop Bebas Crash)
            if (targetPackages != null && !targetPackages.trim().isEmpty()) {
                String[] pkgs = targetPackages.split("[,;\\n\\s]+");
                int added = 0;
                for (String pkg : pkgs) {
                    String clean = pkg.trim();
                    if (!clean.isEmpty()) {
                        try {
                            builder.addAllowedApplication(clean);
                            added++;
                            Core.addLog("INFO", "Proxy Target: " + clean);
                        } catch (PackageManager.NameNotFoundException ignored) {}
                    }
                }
                if (added > 0) {
                    Core.addLog("INFO", String.format("Whitelist Mode Active (%d apps proxied, Termux safely excluded)", added));
                }
            } else {
                // MODE 2: GLOBAL MODE (Bypass Termux)
                try {
                    builder.addDisallowedApplication(getPackageName());
                } catch (PackageManager.NameNotFoundException ignored) {}

                PackageManager pm = getPackageManager();
                List<ApplicationInfo> installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
                for (ApplicationInfo app : installedApps) {
                    String pkg = app.packageName.toLowerCase();
                    if (pkg.contains("termux") || pkg.contains("injector") || pkg.contains("httpcustom") || pkg.contains("netmod") || pkg.contains("v2ray")) {
                        try {
                            builder.addDisallowedApplication(app.packageName);
                        } catch (PackageManager.NameNotFoundException ignored) {}
                    }
                }
            }

            vpnInterface = builder.establish();
            if (vpnInterface == null) {
                Core.addLog("ERROR", "Failed to establish VPN interface.");
                stopSelf();
                return;
            }

            int fd = vpnInterface.detachFd();
            Core.addLog("INFO", "TUN Bound. Forwarding to " + socksAddr);

            boolean success = Core.startEngine(fd, socksAddr, dnsAddr, useInternalDns);
            if (!success) {
                Core.addLog("ERROR", "Go Kernel rejected parameters.");
                shutdownVpn();
            }

        } catch (Exception e) {
            Core.addLog("ERROR", "Setup Error: " + e.getMessage());
            shutdownVpn();
        }
    }

    private void startForegroundNotification(boolean internalDns) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Neural VPN Active Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Neural VPN Micro-Kernel");
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
                .setContentTitle("Neural VPN Active")
                .setContentText("Micro-Kernel Protected (RAM Safe)")
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
