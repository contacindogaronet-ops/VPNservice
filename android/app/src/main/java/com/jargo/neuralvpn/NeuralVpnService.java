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
    public static final String EXTRA_BYPASS_TERMUX = "EXTRA_BYPASS_TERMUX";
    public static final String EXTRA_CUSTOM_BYPASS = "EXTRA_CUSTOM_BYPASS";

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
            boolean bypassTermux = intent.getBooleanExtra(EXTRA_BYPASS_TERMUX, true);
            String customBypass = intent.getStringExtra(EXTRA_CUSTOM_BYPASS);

            if (socksHost == null || socksHost.isEmpty()) {
                socksHost = "127.0.0.3";
            }
            if (dnsAddr == null || dnsAddr.isEmpty()) {
                dnsAddr = "10.10.0.2";
            }

            final String fullSocks = socksHost + ":" + socksPort;
            final String finalDns = dnsAddr;

            startForegroundNotification(useInternalDns);

            executor.execute(() -> launchEngine(fullSocks, finalDns, useInternalDns, bypassTermux, customBypass));
        }

        return START_STICKY;
    }

    private synchronized void launchEngine(String socksAddr, String dnsAddr, boolean useInternalDns, boolean bypassTermux, String customBypass) {
        if (vpnInterface != null) {
            Core.addLog("WARN", "Tunnel interface already active.");
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

            // Bypass aplikasi VPN sendiri
            try {
                builder.addDisallowedApplication(getPackageName());
            } catch (PackageManager.NameNotFoundException ignored) {}

            // Auto-Bypass Termux & Injector Tunneling
            if (bypassTermux) {
                PackageManager pm = getPackageManager();
                List<ApplicationInfo> installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
                for (ApplicationInfo app : installedApps) {
                    String pkg = app.packageName.toLowerCase();
                    if (pkg.contains("termux") || pkg.contains("injector") || pkg.contains("httpcustom") || pkg.contains("netmod") || pkg.contains("v2ray")) {
                        try {
                            builder.addDisallowedApplication(app.packageName);
                            Core.addLog("INFO", "Anti-Loop Bypassed: " + app.packageName);
                        } catch (PackageManager.NameNotFoundException ignored) {}
                    }
                }
            }

            // Bypass package kustom
            if (customBypass != null && !customBypass.trim().isEmpty()) {
                String[] extraPackages = customBypass.split("[,;\\n]+");
                for (String extraPkg : extraPackages) {
                    String cleanPkg = extraPkg.trim();
                    if (!cleanPkg.isEmpty()) {
                        try {
                            builder.addDisallowedApplication(cleanPkg);
                            Core.addLog("INFO", "Custom Bypassed: " + cleanPkg);
                        } catch (PackageManager.NameNotFoundException e) {
                            Core.addLog("WARN", "Package not found: " + cleanPkg);
                        }
                    }
                }
            }

            vpnInterface = builder.establish();
            if (vpnInterface == null) {
                Core.addLog("ERROR", "Failed to establish Android VPN interface.");
                stopSelf();
                return;
            }

            int fd = vpnInterface.detachFd();
            Core.addLog("INFO", "Native TUN bound on FD " + fd + ". Upstream: " + socksAddr);

            boolean success = Core.startEngine(fd, socksAddr, dnsAddr, useInternalDns);
            if (!success) {
                Core.addLog("ERROR", "Go Kernel start failed.");
                shutdownVpn();
            }

        } catch (Exception e) {
            Core.addLog("ERROR", "Fatal exception in VpnService: " + e.getMessage());
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
            channel.setDescription("GoMobile TUN SOCKS5 Kernel");
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

        String desc = internalDns ? "Proxy Internal AI DNS Active" : "Custom DNS Active";

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Neural VPN Active")
                .setContentText(desc)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(activityPendingIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", stopPendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        startForeground(NOTIFICATION_ID, notification);
    }

    private synchronized void shutdownVpn() {
        Core.addLog("INFO", "Shutting down VPN interface...");
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
        Core.addLog("WARN", "VPN credentials revoked.");
        shutdownVpn();
        super.onRevoke();
    }
}
