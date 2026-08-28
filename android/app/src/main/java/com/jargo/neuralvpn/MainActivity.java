package com.jargo.neuralvpn;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.net.TrafficStats;
import android.net.Uri;
import android.net.VpnService;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Switch;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Locale;
import core.Core; // 🔴 Wajib agar bisa memanggil jembatan Golang!

public class MainActivity extends Activity {
    private TextView statusText, statusIcon, connectBtn;
    private TextView tvCpu, tvRam, tvTemp, tvNet, tvLogs;
    private View viewHome, viewRules, viewLogs, btnUploadRules;
    private TextView navHome, navRules, navLogs;
    private Switch switchTele, switchVvip, switchLogs;
    
    private boolean isConnected = false;
    private boolean isLogging = false;
    
    private Handler telemetryHandler = new Handler(Looper.getMainLooper());
    private long lastRxBytes = 0, lastTxBytes = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        viewHome = findViewById(R.id.viewHome);
        viewRules = findViewById(R.id.viewRules);
        viewLogs = findViewById(R.id.viewLogs);
        
        navHome = findViewById(R.id.navHome);
        navRules = findViewById(R.id.navRules);
        navLogs = findViewById(R.id.navLogs);

        statusText = findViewById(R.id.statusText);
        statusIcon = findViewById(R.id.statusIcon);
        connectBtn = findViewById(R.id.connectBtn);
        tvCpu = findViewById(R.id.tvCpu);
        tvRam = findViewById(R.id.tvRam);
        tvTemp = findViewById(R.id.tvTemp);
        tvNet = findViewById(R.id.tvNet);
        tvLogs = findViewById(R.id.tvLogs);
        
        switchTele = findViewById(R.id.switchTele);
        switchVvip = findViewById(R.id.switchVvip);
        switchLogs = findViewById(R.id.switchLogs);
        btnUploadRules = findViewById(R.id.btnUploadRules);

        setupNavigation();
        setupSwitches();

        lastRxBytes = TrafficStats.getTotalRxBytes();
        lastTxBytes = TrafficStats.getTotalTxBytes();
        telemetryHandler.post(telemetryRunnable);

        connectBtn.setOnClickListener(v -> {
            if (!isConnected) requestVpnPermission();
            else stopVpnEngine();
        });

        // 🔴 KUNCI ARSITEKTUR: Buka File Manager Android
        btnUploadRules.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("text/plain");
            startActivityForResult(intent, 2);
        });
    }

    private void setupNavigation() {
        navHome.setOnClickListener(v -> switchTab(0));
        navRules.setOnClickListener(v -> switchTab(1));
        navLogs.setOnClickListener(v -> switchTab(2));
    }

    private void switchTab(int index) {
        viewHome.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        viewRules.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        viewLogs.setVisibility(index == 2 ? View.VISIBLE : View.GONE);

        navHome.setTextColor(Color.parseColor(index == 0 ? "#00E676" : "#777777"));
        navRules.setTextColor(Color.parseColor(index == 1 ? "#00BBFF" : "#777777"));
        navLogs.setTextColor(Color.parseColor(index == 2 ? "#FF3D00" : "#777777"));
    }

    private void setupSwitches() {
        switchTele.setOnCheckedChangeListener((btn, isChecked) -> appendLog(isChecked ? "MTProto Boost [ENABLED]" : "MTProto Boost [DISABLED]"));
        switchVvip.setOnCheckedChangeListener((btn, isChecked) -> appendLog(isChecked ? "VVIP Ad-Block DPI [ENABLED]" : "VVIP Ad-Block DPI [DISABLED]"));
        switchLogs.setOnCheckedChangeListener((btn, isChecked) -> {
            isLogging = isChecked;
            appendLog(isLogging ? "Live Traffic Stream [ON]" : "Live Traffic Stream [PAUSED]");
        });
    }

    private void appendLog(String msg) {
        if (!isLogging && !msg.contains("ON")) return;
        tvLogs.append("\n> " + msg);
    }

    private void requestVpnPermission() {
        Intent intent = VpnService.prepare(this);
        if (intent != null) startActivityForResult(intent, 1);
        else startVpnEngine();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        // Balasan dari Negosiasi VPN Kernel (Kode 1)
        if (requestCode == 1 && resultCode == RESULT_OK) {
            startVpnEngine();
        } 
        // 🔴 Balasan dari File Manager Android (Kode 2)
        else if (requestCode == 2 && resultCode == RESULT_OK && data != null) {
            readRulesFile(data.getData());
        }
    }

    private void readRulesFile(Uri uri) {
        try {
            InputStream inputStream = getContentResolver().openInputStream(uri);
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            inputStream.close();
            
            // ⚡ Tembakkan isi teks ke RAM Golang!
            Core.loadRules(sb.toString());
            appendLog("SYSTEM: Matriks Rules berhasil disuntikkan ke Kernel Golang.");
            
        } catch (Exception e) {
            appendLog("ERROR: Gagal membaca file rules - " + e.getMessage());
        }
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
        appendLog("VpnService [ESTABLISHED] - Layer 3 TUN open.");
    }

    private void stopVpnEngine() {
        stopService(new Intent(this, NeuralVpnService.class));
        isConnected = false;
        statusIcon.setText("🔴");
        statusText.setText("SYSTEM STANDBY");
        statusText.setTextColor(Color.parseColor("#888888"));
        connectBtn.setBackgroundResource(R.drawable.bg_button);
        connectBtn.setText("TAP TO IGNITE");
        connectBtn.setTextColor(Color.BLACK);
        appendLog("VpnService [DESTROYED] - Connection Killed.");
    }

    // Mesin Penarik Data Real-Time
    private Runnable telemetryRunnable = new Runnable() {
        @Override
        public void run() {
            // 1. Tarik Log dari Golang (Jika saklar log nyala)
            if (isLogging) {
                String goLogs = Core.pullLogs(); // ⚡ Memanggil fungsi bridge.go
                if (goLogs != null && !goLogs.isEmpty()) {
                    tvLogs.append(goLogs);
                }
            }

            // 2. Pembaruan UI Telemetri hanya jika sedang di Tab Home (Hemat CPU)
            if (viewHome.getVisibility() == View.VISIBLE) {
                ActivityManager actManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
                if (actManager != null) {
                    actManager.getMemoryInfo(memInfo);
                    tvRam.setText(((memInfo.totalMem - memInfo.availMem) / (1024 * 1024)) + " MB");
                }

                Intent intent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (intent != null) {
                    float temp = ((float) intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)) / 10;
                    tvTemp.setText(String.format(Locale.US, "%.1f °C", temp));
                }

                long currentRx = TrafficStats.getTotalRxBytes();
                long currentTx = TrafficStats.getTotalTxBytes();
                tvNet.setText(((currentTx - lastTxBytes)/1024) + " / " + ((currentRx - lastRxBytes)/1024));
                lastRxBytes = currentRx;
                lastTxBytes = currentTx;
                
                tvCpu.setText("ACTIVE");
            }
            telemetryHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onDestroy() {
        super.onDestroy();
        telemetryHandler.removeCallbacks(telemetryRunnable);
    }
}
