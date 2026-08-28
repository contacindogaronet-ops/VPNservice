package core

import (
	"encoding/binary"
	"fmt"
)

func AnalyzeDNS(packet []byte, srcIP string, dstIP string) {
	ihl := int(packet[0]&0x0F) * 4
	if len(packet) < ihl+8 {
		return
	}

	srcPort := binary.BigEndian.Uint16(packet[ihl : ihl+2])
	dstPort := binary.BigEndian.Uint16(packet[ihl+2 : ihl+4])

	if dstPort == 53 {
		logMsg := fmt.Sprintf("🎯 [DNS AI] Mencegat UDP:53 | %s:%d -> %s", srcIP, srcPort, dstIP)
		AddLog(logMsg)
		
		// Eksekusi Logika Sinkhole (0.0.0.0) akan berlanjut di sini
	} else {
		// Logika UDP umum (Game / QUIC)
		// logMsg := fmt.Sprintf("🌐 [UDP] %s:%d -> %s:%d", srcIP, srcPort, dstIP, dstPort)
		// AddLog(logMsg)
	}
}
