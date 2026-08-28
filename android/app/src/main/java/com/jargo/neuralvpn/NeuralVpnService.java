package com.jargo.neuralvpn;

import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import core.Core;

public class NeuralVpnService extends VpnService {
    private ParcelFileDescriptor vpnInterface = null;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Builder builder = new Builder();
        builder.setMtu(1500);
        
        // 🔴 KUNCI ARSITEKTUR: Absolute Bypass. 
        // Kita HANYA merutekan IP internal VPN itu sendiri. 
        // Semua paket internet asli (YouTube, Chrome) akan lewat jalur WiFi/Seluler normal.
        builder.addAddress("10.0.0.2", 24);
        builder.addRoute("10.0.0.2", 32); 
        
        try {
            vpnInterface = builder.establish();
            if (vpnInterface != null) {
                int fd = vpnInterface.getFd();
                new Thread(() -> Core.startEngine(fd)).start();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return START_STICKY; // Tetap hidup sampai stopService dipanggil
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            if (vpnInterface != null) {
                vpnInterface.close(); // Hancurkan File Descriptor!
                vpnInterface = null;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
