package com.jargo.neuralvpn;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.VpnService;
import android.os.Build;
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
        // 🔴 KUNCI ARSITEKTUR: Meminta izin Kernel Android secara eksplisit
        Intent intent = VpnService.prepare(this);
        if (intent != null) {
            startActivityForResult(intent, 1);
        } else {
            startVpnEngine(); // Izin sudah ada, langsung eksekusi
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(vpnIntent);
        } else {
            startService(vpnIntent);
        }
        
        // Update UI Interaktif
        statusText.setText("STATUS: KUL ENGINE AKTIF (LAYER 3)");
        statusText.setTextColor(Color.GREEN);
        connectBtn.setText("CONNECTED");
        connectBtn.setEnabled(false); // Kunci tombol
    }
}
