package core

import (
	"net"
	"os"
)

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

		protocol := packet[9]
		srcIP := net.IPv4(packet[12], packet[13], packet[14], packet[15])
		dstIP := net.IPv4(packet[16], packet[17], packet[18], packet[19])

		if protocol == 6 {
			go routeToSOCKS(packet)
		} else if protocol == 17 {
			go AnalyzeDNS(packet, srcIP.String(), dstIP.String())
		}
	}
}
