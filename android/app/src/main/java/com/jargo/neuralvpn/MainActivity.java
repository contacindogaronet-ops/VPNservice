package com.jargo.neuralvpn;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.VpnService;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

public class MainActivity extends Activity {
    private TextView statusText;
    private Button connectBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        connectBtn = findViewById(R.id.connectBtn);

        connectBtn.setOnClickListener(v -> requestVpnPermission());
    }

    private void requestVpnPermission() {
        Intent intent = VpnService.prepare(this);
        if (intent != null) {
            startActivityForResult(intent, 1);
        } else {
            startVpnEngine();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == 1 && resultCode == RESULT_OK) {
            startVpnEngine();
        } else {
            statusText.setText("STATUS: IZIN DITOLAK KERNEL");
        }
    }

    private void startVpnEngine() {
        Intent vpnIntent = new Intent(this, NeuralVpnService.class);
        // 🔴 KUNCI ARSITEKTUR: Jangan gunakan startForegroundService di Android 14 untuk VPN
        startService(vpnIntent);
        
        statusText.setText("STATUS: KUL ENGINE AKTIF");
        statusText.setTextColor(Color.GREEN);
        connectBtn.setText("CONNECTED");
        connectBtn.setEnabled(false);
    }
}
