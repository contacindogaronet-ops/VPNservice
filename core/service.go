package core

import (
	"fmt"
	"net"
	"os"
)

// StartEngine dipanggil langsung oleh Java VpnService
func StartEngine(fd int) {
	AddLog("🔥 [SYSTEM] KUL Monolithic Service Terinisialisasi (Pure Go)")
	AddLog("📡 [SYSTEM] Menghubungkan langsung ke File Descriptor Kernel...")

	tunFile := os.NewFile(uintptr(fd), "tun")
	if tunFile == nil {
		AddLog("❌ [FATAL] Gagal mengambil alih File Descriptor Android.")
		return
	}
	defer tunFile.Close()

	buffer := make([]byte, 65535)

	for {
		n, err := tunFile.Read(buffer)
		if err != nil {
			AddLog("🛑 [SYSTEM] Pipa TUN Terputus oleh sistem operasi.")
			break
		}

		packet := buffer[:n]
		if len(packet) < 20 {
			continue
		}

		// Ekstraksi Protokol & IP (Header IPv4)
		protocol := packet[9]
		
		// Parsing IP Asal dan Tujuan secara bitwise
		srcIP := net.IPv4(packet[12], packet[13], packet[14], packet[15])
		dstIP := net.IPv4(packet[16], packet[17], packet[18], packet[19])

		if protocol == 6 {
			// TCP Traffic (Hanya log sebagian kecil agar UI tidak lag)
			// AddLog(fmt.Sprintf("⚡ [TCP] %s -> %s", srcIP.String(), dstIP.String()))
			go routeToSOCKS(packet)
		} else if protocol == 17 {
			// UDP / DNS Traffic
			go AnalyzeDNS(packet, srcIP.String(), dstIP.String())
		}
	}
}
