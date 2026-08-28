package com.jargo.neuralvpn;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.VpnService;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

public class MainActivity extends Activity {
    private TextView statusText, statusIcon, connectBtn;
    private boolean isConnected = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        statusIcon = findViewById(R.id.statusIcon);
        connectBtn = findViewById(R.id.connectBtn);

        connectBtn.setOnClickListener(v -> {
            if (!isConnected) {
                requestVpnPermission();
            } else {
                // TODO: Logika pemutusan VPN (Disconnect) akan ditanam di sini
            }
        });
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
            statusText.setText("ACCESS DENIED");
            statusIcon.setText("❌");
        }
    }

    private void startVpnEngine() {
        Intent vpnIntent = new Intent(this, NeuralVpnService.class);
        startService(vpnIntent);
        
        isConnected = true;
        
        // Transformasi Visual (Dashboard Aktif)
        statusIcon.setText("🟢");
        statusText.setText("SECURE TUNNEL ACTIVE");
        statusText.setTextColor(Color.parseColor("#00E676"));
        
        connectBtn.setBackgroundResource(R.drawable.bg_button_off);
        connectBtn.setText("DISCONNECT");
        connectBtn.setTextColor(Color.WHITE);
    }
}
