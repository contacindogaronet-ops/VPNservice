package com.jargo.neuralvpn;

import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import core.Core;

public class NeuralVpnService extends VpnService {
    private ParcelFileDescriptor vpnInterface = null;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Eksekutor Sinyal Kematian
        if (intent != null && "STOP_VPN".equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        Builder builder = new Builder();
        builder.setMtu(1500);
        builder.addAddress("10.0.0.2", 24);
        
        // 🔴 KUNCI ARSITEKTUR (SPLIT-TUNNELING):
        // Hapus addRoute("0.0.0.0", 0) agar internet normal tidak tertelan blackhole.
        // Hanya arahkan lalu lintas IP DNS (10.0.0.3) ke mesin Golang.
        builder.addRoute("10.0.0.3", 32); 
        builder.addDnsServer("10.0.0.3");
        
        try {
            vpnInterface = builder.establish();
            if (vpnInterface != null) {
                int fd = vpnInterface.getFd();
                new Thread(() -> Core.startEngine(fd)).start();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            // Menutup File Descriptor secara paksa akan membuat 
            // tunFile.Read() di Golang langsung menghasilkan error dan berhenti!
            if (vpnInterface != null) {
                vpnInterface.close();
                vpnInterface = null;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
