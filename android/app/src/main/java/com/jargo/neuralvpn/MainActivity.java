package com.jargo.neuralvpn;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.net.TrafficStats;
import android.net.VpnService;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import java.util.Locale;

public class MainActivity extends Activity {
    private TextView statusText, statusIcon, connectBtn;
    private TextView tvCpu, tvRam, tvTemp, tvNet;
    private boolean isConnected = false;
    
    // Mesin Telemetri
    private Handler telemetryHandler = new Handler(Looper.getMainLooper());
    private long lastRxBytes = 0, lastTxBytes = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        statusIcon = findViewById(R.id.statusIcon);
        connectBtn = findViewById(R.id.connectBtn);
        
        tvCpu = findViewById(R.id.tvCpu);
        tvRam = findViewById(R.id.tvRam);
        tvTemp = findViewById(R.id.tvTemp);
        tvNet = findViewById(R.id.tvNet);

        lastRxBytes = TrafficStats.getTotalRxBytes();
        lastTxBytes = TrafficStats.getTotalTxBytes();
        telemetryHandler.post(telemetryRunnable); // Nyalakan Radar

        connectBtn.setOnClickListener(v -> {
            if (!isConnected) requestVpnPermission();
            else stopVpnEngine();
        });
    }

    private void requestVpnPermission() {
        Intent intent = VpnService.prepare(this);
        if (intent != null) startActivityForResult(intent, 1);
        else startVpnEngine();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == 1 && resultCode == RESULT_OK) startVpnEngine();
    }

    private void startVpnEngine() {
        startService(new Intent(this, NeuralVpnService.class));
        isConnected = true;
        statusIcon.setText("🟢");
        statusText.setText("SECURE TUNNEL ACTIVE");
        statusText.setTextColor(Color.parseColor("#00E676"));
        connectBtn.setBackgroundResource(R.drawable.bg_button_off);
        connectBtn.setText("DISCONNECT");
        connectBtn.setTextColor(Color.WHITE);
    }

    private void stopVpnEngine() {
        // 🔴 KUNCI ARSITEKTUR: Bantai Zombie Process secara absolut
        stopService(new Intent(this, NeuralVpnService.class));
        isConnected = false;
        statusIcon.setText("🔴");
        statusText.setText("SYSTEM STANDBY");
        statusText.setTextColor(Color.parseColor("#888888"));
        connectBtn.setBackgroundResource(R.drawable.bg_button);
        connectBtn.setText("TAP TO IGNITE");
        connectBtn.setTextColor(Color.BLACK);
    }

    // --- LOOP TELEMETRI REAL-TIME ---
    private Runnable telemetryRunnable = new Runnable() {
        @Override
        public void run() {
            // 1. RAM Usage
            ActivityManager actManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
            if (actManager != null) {
                actManager.getMemoryInfo(memInfo);
                long usedMemMB = (memInfo.totalMem - memInfo.availMem) / (1024 * 1024);
                tvRam.setText(usedMemMB + " MB");
            }

            // 2. Temperature (Thermal)
            Intent intent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (intent != null) {
                float temp = ((float) intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)) / 10;
                tvTemp.setText(String.format(Locale.US, "%.1f °C", temp));
            }

            // 3. Network I/O (TX/RX Kecepatan)
            long currentRx = TrafficStats.getTotalRxBytes();
            long currentTx = TrafficStats.getTotalTxBytes();
            long rxSpeed = (currentRx - lastRxBytes) / 1024; // KB/s
            long txSpeed = (currentTx - lastTxBytes) / 1024; // KB/s
            lastRxBytes = currentRx;
            lastTxBytes = currentTx;
            tvNet.setText(txSpeed + " / " + rxSpeed);

            // 4. CPU (Android modern memblokir /proc/stat, kita pakai estimasi aman)
            tvCpu.setText("ACTIVE");

            // Ulangi setiap 1 detik
            telemetryHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onDestroy() {
        super.onDestroy();
        telemetryHandler.removeCallbacks(telemetryRunnable);
    }
}
