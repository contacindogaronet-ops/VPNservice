package com.jargo.neuralvpn;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import core.Core;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "NeuralVPN";
    private static final String PREFS_NAME = "NeuralVpnPrefs";

    private View layoutHome;
    private View layoutRules;
    private View layoutLogs;

    private View navHome;
    private View navRules;
    private View navLogs;

    private TextView tvStatusBadge;
    private EditText etSocksHost;
    private EditText etSocksPort;
    private EditText etDnsAddr;
    private SwitchCompat switchBypassTermux;
    private EditText etCustomBypass;
    private Button btnToggleVpn;

    private Button btnPresetTermux10808;
    private Button btnPresetTermux1080;

    private TextView tvRulesStats;
    private TextView tvRulesContent;
    private Button btnPickRules;
    private Button btnClearRules;

    private TextView tvLogsConsole;
    private ScrollView svLogsScroll;
    private Button btnClearLogs;
    private Button btnCopyLogs;
    private SwitchCompat switchAutoScroll;

    private SharedPreferences prefs;
    private final Handler logPollHandler = new Handler(Looper.getMainLooper());
    private boolean isPollingLogs = true;

    private final ActivityResultLauncher<Intent> vpnPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) {
                    startVpnService();
                } else {
                    Toast.makeText(this, "VPN permission denied by user", Toast.LENGTH_SHORT).show();
                }
            });

    private final ActivityResultLauncher<Intent> filePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        importRulesFromUri(uri);
                    }
                }
            });

    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {});

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_main);
            prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

            initViews();
            loadSavedPreferences();
            setupNavigation();
            setupActions();
            requestSystemPermissions();
            startLogPoller();
        } catch (Throwable t) {
            Log.e(TAG, "Fatal error on onCreate: ", t);
            Toast.makeText(this, "Startup error: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void initViews() {
        layoutHome = findViewById(R.id.container_home);
        layoutRules = findViewById(R.id.container_rules);
        layoutLogs = findViewById(R.id.container_logs);

        navHome = findViewById(R.id.btn_nav_home);
        navRules = findViewById(R.id.btn_nav_rules);
        navLogs = findViewById(R.id.btn_nav_logs);

        tvStatusBadge = findViewById(R.id.tv_status_badge);
        etSocksHost = findViewById(R.id.et_socks_host);
        etSocksPort = findViewById(R.id.et_socks_port);
        etDnsAddr = findViewById(R.id.et_dns_addr);
        switchBypassTermux = findViewById(R.id.switch_bypass_termux);
        etCustomBypass = findViewById(R.id.et_custom_bypass);
        btnToggleVpn = findViewById(R.id.btn_toggle_vpn);

        btnPresetTermux10808 = findViewById(R.id.btn_preset_termux_10808);
        btnPresetTermux1080 = findViewById(R.id.btn_preset_termux_1080);

        tvRulesStats = findViewById(R.id.tv_rules_stats);
        tvRulesContent = findViewById(R.id.tv_rules_content);
        btnPickRules = findViewById(R.id.btn_pick_rules);
        btnClearRules = findViewById(R.id.btn_clear_rules);

        tvLogsConsole = findViewById(R.id.tv_logs_console);
        svLogsScroll = findViewById(R.id.sv_logs_scroll);
        btnClearLogs = findViewById(R.id.btn_clear_logs);
        btnCopyLogs = findViewById(R.id.btn_copy_logs);
        switchAutoScroll = findViewById(R.id.switch_autoscroll);

        boolean running = false;
        try {
            running = Core.isRunning();
        } catch (Throwable ignored) {}
        updateVpnUiState(running);
    }

    private void loadSavedPreferences() {
        etSocksHost.setText(prefs.getString("socks_host", "127.0.0.1"));
        etSocksPort.setText(prefs.getString("socks_port", "10808"));
        etDnsAddr.setText(prefs.getString("dns_addr", "1.1.1.1"));
        switchBypassTermux.setChecked(prefs.getBoolean("bypass_termux", true));
        etCustomBypass.setText(prefs.getString("custom_bypass", ""));
    }

    private void savePreferences() {
        prefs.edit()
                .putString("socks_host", etSocksHost.getText().toString().trim())
                .putString("socks_port", etSocksPort.getText().toString().trim())
                .putString("dns_addr", etDnsAddr.getText().toString().trim())
                .putBoolean("bypass_termux", switchBypassTermux.isChecked())
                .putString("custom_bypass", etCustomBypass.getText().toString().trim())
                .apply();
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
        // Quick presets
        btnPresetTermux10808.setOnClickListener(v -> {
            etSocksHost.setText("127.0.0.1");
            etSocksPort.setText("10808");
            switchBypassTermux.setChecked(true);
            Toast.makeText(this, "Preset Termux :10808 applied", Toast.LENGTH_SHORT).show();
        });

        btnPresetTermux1080.setOnClickListener(v -> {
            etSocksHost.setText("127.0.0.1");
            etSocksPort.setText("1080");
            switchBypassTermux.setChecked(true);
            Toast.makeText(this, "Preset Termux :1080 applied", Toast.LENGTH_SHORT).show();
        });

        btnToggleVpn.setOnClickListener(v -> {
            boolean isRunning = false;
            try {
                isRunning = Core.isRunning();
            } catch (Throwable ignored) {}

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
            try {
                Core.loadRules("");
            } catch (Throwable ignored) {}
            tvRulesStats.setText("Rules Loaded: 0");
            tvRulesContent.setText("No rules active. All traffic will route to SOCKS5 proxy default.");
            Toast.makeText(this, "Routing rules cleared", Toast.LENGTH_SHORT).show();
        });

        btnClearLogs.setOnClickListener(v -> tvLogsConsole.setText(""));

        btnCopyLogs.setOnClickListener(v -> {
            String logs = tvLogsConsole.getText().toString();
            if (!logs.isEmpty()) {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("NeuralVPN Logs", logs);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(this, "Logs copied to clipboard", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void prepareAndStartVpn() {
        savePreferences();
        Intent intent = VpnService.prepare(this);
        if (intent != null) {
            vpnPermissionLauncher.launch(intent);
        } else {
            startVpnService();
        }
    }

    private void startVpnService() {
        savePreferences();

        String host = etSocksHost.getText().toString().trim();
        String portStr = etSocksPort.getText().toString().trim();
        String dns = etDnsAddr.getText().toString().trim();
        boolean bypassTermux = switchBypassTermux.isChecked();
        String customBypass = etCustomBypass.getText().toString().trim();

        int port = 10808;
        try {
            if (!portStr.isEmpty()) port = Integer.parseInt(portStr);
        } catch (NumberFormatException ignored) {}

        Intent serviceIntent = new Intent(this, NeuralVpnService.class);
        serviceIntent.setAction(NeuralVpnService.ACTION_START);
        serviceIntent.putExtra(NeuralVpnService.EXTRA_SOCKS_HOST, host);
        serviceIntent.putExtra(NeuralVpnService.EXTRA_SOCKS_PORT, port);
        serviceIntent.putExtra(NeuralVpnService.EXTRA_DNS_ADDR, dns);
        serviceIntent.putExtra(NeuralVpnService.EXTRA_BYPASS_TERMUX, bypassTermux);
        serviceIntent.putExtra(NeuralVpnService.EXTRA_CUSTOM_BYPASS, customBypass);

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
            tvStatusBadge.setText("ACTIVE");
            tvStatusBadge.setTextColor(Color.parseColor("#10B981"));
            btnToggleVpn.setText("DISCONNECT");
            btnToggleVpn.setBackgroundColor(Color.parseColor("#EF4444"));
        } else {
            tvStatusBadge.setText("DISCONNECTED");
            tvStatusBadge.setTextColor(Color.parseColor("#94A3B8"));
            btnToggleVpn.setText("START TUNNEL");
            btnToggleVpn.setBackgroundColor(Color.parseColor("#6366F1"));
        }
    }

    private void importRulesFromUri(Uri uri) {
        try (InputStream is = getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }

            String content = sb.toString();
            long count = Core.loadRules(content);
            tvRulesStats.setText("Rules Loaded: " + count);
            tvRulesContent.setText(content);
            Toast.makeText(this, "Imported " + count + " rules successfully", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Failed loading file: " + e.getMessage(), Toast.LENGTH_LONG).show();
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

    private void startLogPoller() {
        logPollHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isPollingLogs) {
                    try {
                        String logs = Core.pullLogs();
                        if (logs != null && !logs.isEmpty()) {
                            tvLogsConsole.append(logs + "\n");
                            if (switchAutoScroll != null && switchAutoScroll.isChecked()) {
                                svLogsScroll.post(() -> svLogsScroll.fullScroll(View.FOCUS_DOWN));
                            }
                        }
                        updateVpnUiState(Core.isRunning());
                    } catch (Throwable ignored) {}
                }
                logPollHandler.postDelayed(this, 500);
            }
        }, 500);
    }

    @Override
    protected void onDestroy() {
        isPollingLogs = false;
        logPollHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
