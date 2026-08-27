package com.jargo.neuralvpn;
import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = VpnService.prepare(this);
        if (intent != null) {
            startActivityForResult(intent, 0); // Minta Izin OS
        } else {
            onActivityResult(0, Activity.RESULT_OK, null); // Sudah Diizinkan
        }
    }
    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        if (result == RESULT_OK) {
            startService(new Intent(this, NeuralVpnService.class));
            finish(); // Tutup UI, biarkan mesin berjalan di background
        }
    }
}
