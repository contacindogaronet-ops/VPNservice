package com.jargo.neuralvpn;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.concurrent.Executors;
import core.Core;

public class MainActivity extends AppCompatActivity {

    private View layoutHome;
    private View layoutRules;
    private View layoutLogs;

    private View navHome;
    private View navRules;
    private View navLogs;

    private TextView tvStatusBadge;
    private TextView tvLocalPing;
    private TextView tvGlobalPing;
    private Button btnToggleVpn;

    private TextView tvRulesStats;
    private TextView tvRulesContent;
    private Button btnPickRules;
    private Button btnClearRules;

    private TextView tvLogsConsole;
    private ScrollView svLogsScroll;
    private Button btnClearLogs;
    private Button btnCopyLogs;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isPolling = true;

    private final ActivityResultLauncher<Intent> vpnPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) {
                    startVpnService();
                } else {
                    Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show();
                }
            });

    private final ActivityResultLauncher<Intent> filePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) importRulesFromUri(uri);
                }
            });

    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {});

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        setupNavigation();
        setupActions();
        requestSystemPermissions();
        startPolling();
    }

    private void initViews() {
        layoutHome = findViewById(R.id.container_home);
        layoutRules = findViewById(R.id.container_rules);
        layoutLogs = findViewById(R.id.container_logs);

        navHome = findViewById(R.id.btn_nav_home);
        navRules = findViewById(R.id.btn_nav_rules);
        navLogs = findViewById(R.id.btn_nav_logs);

        tvStatusBadge = findViewById(R.id.tv_status_badge);
        tvLocalPing = findViewById(R.id.tv_local_ping);
        tvGlobalPing = findViewById(R.id.tv_global_ping);
        btnToggleVpn = findViewById(R.id.btn_toggle_vpn);

        tvRulesStats = findViewById(R.id.tv_rules_stats);
        tvRulesContent = findViewById(R.id.tv_rules_content);
        btnPickRules = findViewById(R.id.btn_pick_rules);
        btnClearRules = findViewById(R.id.btn_clear_rules);

        tvLogsConsole = findViewById(R.id.tv_logs_console);
        svLogsScroll = findViewById(R.id.sv_logs_scroll);
        btnClearLogs = findViewById(R.id.btn_clear_logs);
        btnCopyLogs = findViewById(R.id.btn_copy_logs);

        boolean running = false;
        try { running = Core.isRunning(); } catch (Throwable ignored) {}
        updateVpnUiState(running);
    }

    private void setupNavigation() {
        navHome.setOnClickListener(v -> switchTab(0));
        navRules.setOnClickListener(v -> switchTab(1));
        navLogs.setOnClickListener(v -> switchTab(2));
        switchTab(0);
    }

    private void switchTab(int index) {
        layoutHome.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        layoutRules.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        layoutLogs.setVisibility(index == 2 ? View.VISIBLE : View.GONE);

        navHome.setAlpha(index == 0 ? 1.0f : 0.4f);
        navRules.setAlpha(index == 1 ? 1.0f : 0.4f);
        navLogs.setAlpha(index == 2 ? 1.0f : 0.4f);
    }

    private void setupActions() {
        btnToggleVpn.setOnClickListener(v -> {
            boolean isRunning = false;
            try { isRunning = Core.isRunning(); } catch (Throwable ignored) {}

            if (isRunning) {
                stopVpnService();
            } else {
                prepareAndStartVpn();
            }
        });

        btnPickRules.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            filePickerLauncher.launch(intent);
        });

        btnClearRules.setOnClickListener(v -> {
            try { Core.loadRules(""); } catch (Throwable ignored) {}
            tvRulesStats.setText("Rules Loaded: 0");
            tvRulesContent.setText("No rules active. All traffic is routed directly by Kernel.");
            Toast.makeText(this, "Rules cleared", Toast.LENGTH_SHORT).show();
        });

        btnClearLogs.setOnClickListener(v -> tvLogsConsole.setText(""));

        btnCopyLogs.setOnClickListener(v -> {
            String logs = tvLogsConsole.getText().toString();
            if (!logs.isEmpty()) {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("NeuralVPN Logs", logs);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(this, "Logs copied", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void prepareAndStartVpn() {
        Intent intent = VpnService.prepare(this);
        if (intent != null) {
            vpnPermissionLauncher.launch(intent);
        } else {
            startVpnService();
        }
    }

    private void startVpnService() {
        Intent serviceIntent = new Intent(this, NeuralVpnService.class);
        serviceIntent.setAction(NeuralVpnService.ACTION_START);
        serviceIntent.putExtra(NeuralVpnService.EXTRA_SOCKS_ADDR, "127.0.0.3:2007");
        serviceIntent.putExtra(NeuralVpnService.EXTRA_DNS_ADDR, "1.1.1.1");
        ContextCompat.startForegroundService(this, serviceIntent);
        updateVpnUiState(true);
    }

    private void stopVpnService() {
        Intent serviceIntent = new Intent(this, NeuralVpnService.class);
        serviceIntent.setAction(NeuralVpnService.ACTION_STOP);
        startService(serviceIntent);
        updateVpnUiState(false);
    }

    private void updateVpnUiState(boolean running) {
        if (running) {
            tvStatusBadge.setText("MONOLITH ACTIVE");
            tvStatusBadge.setTextColor(Color.parseColor("#10B981"));
            btnToggleVpn.setText("DISCONNECT");
            btnToggleVpn.setBackgroundColor(Color.parseColor("#EF4444"));
        } else {
            tvStatusBadge.setText("DISCONNECTED");
            tvStatusBadge.setTextColor(Color.parseColor("#94A3B8"));
            btnToggleVpn.setText("START ENGINE");
            btnToggleVpn.setBackgroundColor(Color.parseColor("#6366F1"));
            tvLocalPing.setText("- ms");
            tvGlobalPing.setText("- ms");
        }
    }

    private void importRulesFromUri(Uri uri) {
        try (InputStream is = getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append("\n");

            String content = sb.toString();
            long count = Core.loadRules(content);
            tvRulesStats.setText("Rules Loaded: " + count);
            tvRulesContent.setText(content);
            Toast.makeText(this, "Imported " + count + " rules", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Load failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void requestSystemPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
    }

    private void startPolling() {
        // Polling Logs
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isPolling) {
                    try {
                        String logs = Core.pullLogs();
                        if (logs != null && !logs.isEmpty()) {
                            tvLogsConsole.append(logs + "\n");
                            svLogsScroll.post(() -> svLogsScroll.fullScroll(View.FOCUS_DOWN));
                        }
                        updateVpnUiState(Core.isRunning());
                    } catch (Throwable ignored) {}
                }
                mainHandler.postDelayed(this, 500);
            }
        }, 500);

        // Polling Ping Tester (Local & Global)
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
            if (!isPolling) return;
            boolean running = false;
            try { running = Core.isRunning(); } catch (Throwable ignored) {}

            if (running) {
                int localPing = (int) Core.testLocalPing("127.0.0.3:2007");
                int globalPing = (int) Core.testGlobalPing("1.1.1.1:80");

                mainHandler.post(() -> {
                    if (localPing >= 0) {
                        tvLocalPing.setText(localPing + " ms");
                        tvLocalPing.setTextColor(Color.parseColor("#10B981"));
                    } else {
                        tvLocalPing.setText("Timeout");
                        tvLocalPing.setTextColor(Color.parseColor("#EF4444"));
                    }

                    if (globalPing >= 0) {
                        tvGlobalPing.setText(globalPing + " ms");
                        tvGlobalPing.setTextColor(Color.parseColor("#38BDF8"));
                    } else {
                        tvGlobalPing.setText("Timeout");
                        tvGlobalPing.setTextColor(Color.parseColor("#EF4444"));
                    }
                });
            }
        }, 1, 2, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Override
    protected void onDestroy() {
        isPolling = false;
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
