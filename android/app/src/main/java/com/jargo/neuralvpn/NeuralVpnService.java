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
        builder.addAddress("10.0.0.2", 24);
        builder.addRoute("0.0.0.0", 0);
        builder.addDnsServer("10.0.0.3");
        
        try {
            vpnInterface = builder.establish();
            if (vpnInterface != null) {
                int fd = vpnInterface.getFd();
                // ⚡ Java langsung melempar File Descriptor ke Otak Golang Anda
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
            if (vpnInterface != null) vpnInterface.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
