package com.jargo.neuralvpn;
import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import core.Core; // 🔴 Import biner Golang hasil GoMobile

public class NeuralVpnService extends VpnService {
    private ParcelFileDescriptor vpnInterface = null;
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Builder builder = new Builder();
        builder.setMtu(1500);
        builder.addAddress("10.0.0.2", 24);
        builder.addRoute("0.0.0.0", 0); // Bajak semua lalu lintas
        builder.addDnsServer("10.0.0.3"); // Arahkan ke Neural DNS Anda
        
        try {
            vpnInterface = builder.establish();
            if (vpnInterface != null) {
                int fd = vpnInterface.getFd();
                // ⚡ Jembatan Ekstrem: Lempar FD OS ke Goroutine!
                new Thread(() -> Core.startVpnEngine(fd)).start();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return START_STICKY;
    }
}
