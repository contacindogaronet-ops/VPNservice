package core

import (
	"os"
	"github.com/rs/zerolog/log"
)

// StartEngine dipanggil langsung oleh Java VpnService
func StartEngine(fd int) {
	log.Info().Msg("🔥 KUL Monolithic Service Terinisialisasi (Pure Go)")

	tunFile := os.NewFile(uintptr(fd), "tun")
	if tunFile == nil {
		log.Error().Msg("FATAL: Gagal mengambil alih File Descriptor Android.")
		return
	}
	defer tunFile.Close()

	// Arsitektur pembacaan Zero-Alloc (Native net.Conn.Read style)
	buffer := make([]byte, 65535)

	for {
		n, err := tunFile.Read(buffer)
		if err != nil {
			log.Error().Err(err).Msg("Pipa TUN Terputus oleh sistem operasi.")
			break
		}

		packet := buffer[:n]
		if len(packet) < 20 {
			continue
		}

		// Ekstraksi Protokol dari Header IPv4
		protocol := packet[9]
		if protocol == 6 {
			// Lempar ke mesin gVisor/TCP Relay (Asinkron)
			go routeToSOCKS(packet)
		} else if protocol == 17 {
			// Lempar ke Otak DNS UDP (Asinkron)
			go AnalyzeDNS(packet)
		}
	}
}
