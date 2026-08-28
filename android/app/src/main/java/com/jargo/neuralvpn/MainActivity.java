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
import android.view.View;
import android.widget.Switch;
import android.widget.TextView;
import java.util.Locale;

public class MainActivity extends Activity {
    // UI Elements
    private TextView statusText, statusIcon, connectBtn;
    private TextView tvCpu, tvRam, tvTemp, tvNet, tvLogs;
    private View viewHome, viewRules, viewLogs;
    private TextView navHome, navRules, navLogs;
    private Switch switchTele, switchVvip, switchLogs;
    
    // State
    private boolean isConnected = false;
    private boolean isLogging = false;
    
    // Telemetry Engine
    private Handler telemetryHandler = new Handler(Looper.getMainLooper());
    private long lastRxBytes = 0, lastTxBytes = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Inisialisasi Tampilan
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

        setupNavigation();
        setupSwitches();

        lastRxBytes = TrafficStats.getTotalRxBytes();
        lastTxBytes = TrafficStats.getTotalTxBytes();
        telemetryHandler.post(telemetryRunnable);

        connectBtn.setOnClickListener(v -> {
            if (!isConnected) requestVpnPermission();
            else stopVpnEngine();
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
        switchTele.setOnCheckedChangeListener((btn, isChecked) -> appendLog(isChecked ? "MTProto Boost 1:1 [ENABLED]" : "MTProto Boost 1:1 [DISABLED]"));
        switchVvip.setOnCheckedChangeListener((btn, isChecked) -> appendLog(isChecked ? "VVIP Ad-Block DPI [ENABLED]" : "VVIP Ad-Block DPI [DISABLED]"));
        switchLogs.setOnCheckedChangeListener((btn, isChecked) -> {
            isLogging = isChecked;
            appendLog(isLogging ? "Kernel Log Stream [ON]" : "Kernel Log Stream [PAUSED]");
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

    private Runnable telemetryRunnable = new Runnable() {
        @Override
        public void run() {
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
