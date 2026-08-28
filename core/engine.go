package core

import (
	"encoding/binary"
	"os"
	"sync"

	"github.com/rs/zerolog/log"
)

// Arsitektur Dual-Pool: 
// 1. Buffer raksasa untuk menangkap MTU IP mentah dari Android
var tunPool = sync.Pool{
	New: func() interface{} {
		b := make([]byte, 65535)
		return &b
	},
}

// 2. Buffer 32KB untuk TCP Relay standar (Anti-Jitter) yang akan kita gunakan nanti
var relayPool32 = sync.Pool{
	New: func() interface{} {
		b := make([]byte, 32*1024)
		return &b
	},
}

func StartEngine(fd int) {
	log.Info().Msg("🔥 Engine KUL v3.0: Layer 3 Interceptor Aktif!")
	
	tun := os.NewFile(uintptr(fd), "tun")
	defer tun.Close()

	for {
		bufPtr := tunPool.Get().(*[]byte)
		packet := *bufPtr

		n, err := tun.Read(packet)
		if err != nil {
			log.Error().Err(err).Msg("Pipa TUN Terputus.")
			tunPool.Put(bufPtr)
			break
		}

		go dissectPacket(packet[:n], bufPtr)
	}
}

func dissectPacket(packet []byte, originalBuf *[]byte) {
	defer tunPool.Put(originalBuf)

	if len(packet) < 20 {
		return // Terlalu kecil untuk header IPv4
	}

	// 1. Ekstraksi Header IPv4
	version := packet[0] >> 4
	if version != 4 {
		return // Abaikan IPv6 untuk saat ini
	}

	ihl := int(packet[0]&0x0F) * 4 // IP Header Length (Biasanya 20 byte)
	protocol := packet[9]

	// 2. Pemisahan Jalur (Bifurcation)
	if protocol == 17 { // UDP
		handleUDP(packet, ihl)
	} else if protocol == 6 { // TCP
		handleTCP(packet, ihl)
	}
}

func handleUDP(packet []byte, iphLen int) {
	if len(packet) < iphLen+8 {
		return // Paket UDP korup
	}

	// Ekstraksi Port dari Header UDP
	srcPort := binary.BigEndian.Uint16(packet[iphLen : iphLen+2])
	dstPort := binary.BigEndian.Uint16(packet[iphLen+2 : iphLen+4])

	if dstPort == 53 {
		log.Info().Msgf("🎯 [DNS AI] Mencegat kueri DNS dari port internal %d", srcPort)
		// TODO: Di sinilah Otak AI SINKHOLE Anda akan membaca payload domain
		// dan membalas dengan IP 0.0.0.0 (Block) atau IP asli (Allow)
	} else {
		// UDP Umum (Game Online, QUIC, dll)
		// log.Debug().Msgf("UDP Traffic: %d -> %d", srcPort, dstPort)
	}
}

func handleTCP(packet []byte, iphLen int) {
	// TCP State Machine akan diletakkan di sini.
	// log.Debug().Msg("TCP Syn/Ack/Data Terdeteksi")
}
