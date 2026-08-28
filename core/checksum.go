package core

import (
	"encoding/binary"
	"net"
)

func Checksum(b []byte) uint16 {
	var sum uint32
	for i := 0; i < len(b)-1; i += 2 {
		sum += uint32(binary.BigEndian.Uint16(b[i : i+2]))
	}
	if len(b)%2 == 1 {
		sum += uint32(b[len(b)-1]) << 8
	}
	for sum > 0xffff {
		sum = (sum >> 16) + (sum & 0xffff)
	}
	return ^uint16(sum)
}

func BuildIPv4Header(srcIP, dstIP net.IP, protocol byte, payloadLen int) []byte {
	totalLen := 20 + payloadLen
	header := make([]byte, 20)
	header[0] = 0x45 // Version 4, IHL 5
	header[1] = 0x00 // DSCP / ECN
	binary.BigEndian.PutUint16(header[2:4], uint16(totalLen))
	binary.BigEndian.PutUint16(header[4:6], 0x1234) // Identification
	binary.BigEndian.PutUint16(header[6:8], 0x4000) // Flags: Don't Fragment
	header[8] = 64                                 // TTL
	header[9] = protocol                           // 6 for TCP, 17 for UDP
	header[10] = 0                                 // Checksum placeholder
	header[11] = 0
	copy(header[12:16], srcIP.To4())
	copy(header[16:20], dstIP.To4())

	cs := Checksum(header)
	binary.BigEndian.PutUint16(header[10:12], cs)
	return header
}

func BuildTCPPacket(srcIP, dstIP net.IP, srcPort, dstPort uint16, seq, ack uint32, flags byte, payload []byte) []byte {
	tcpHeader := make([]byte, 20)
	binary.BigEndian.PutUint16(tcpHeader[0:2], srcPort)
	binary.BigEndian.PutUint16(tcpHeader[2:4], dstPort)
	binary.BigEndian.PutUint32(tcpHeader[4:8], seq)
	binary.BigEndian.PutUint32(tcpHeader[8:12], ack)
	tcpHeader[12] = 0x50 // Data offset (5 words = 20 bytes)
	tcpHeader[13] = flags
	binary.BigEndian.PutUint16(tcpHeader[14:16], 65535) // Window size
	tcpHeader[16] = 0                                   // Checksum placeholder
	tcpHeader[17] = 0
	binary.BigEndian.PutUint16(tcpHeader[18:20], 0) // Urgent pointer

	tcpData := append(tcpHeader, payload...)

	// Hitung TCP pseudo-header checksum
	pseudoHeader := make([]byte, 12+len(tcpData))
	copy(pseudoHeader[0:4], srcIP.To4())
	copy(pseudoHeader[4:8], dstIP.To4())
	pseudoHeader[8] = 0
	pseudoHeader[9] = 6 // IPPROTO_TCP
	binary.BigEndian.PutUint16(pseudoHeader[10:12], uint16(len(tcpData)))
	copy(pseudoHeader[12:], tcpData)

	cs := Checksum(pseudoHeader)
	binary.BigEndian.PutUint16(tcpData[16:18], cs)

	ipHeader := BuildIPv4Header(srcIP, dstIP, 6, len(tcpData))
	return append(ipHeader, tcpData...)
}

func BuildUDPPacket(srcIP, dstIP net.IP, srcPort, dstPort uint16, payload []byte) []byte {
	udpLen := 8 + len(payload)
	udpHeader := make([]byte, 8)
	binary.BigEndian.PutUint16(udpHeader[0:2], srcPort)
	binary.BigEndian.PutUint16(udpHeader[2:4], dstPort)
	binary.BigEndian.PutUint16(udpHeader[4:6], uint16(udpLen))
	udpHeader[6] = 0 // Checksum optional di IPv4
	udpHeader[7] = 0

	udpData := append(udpHeader, payload...)
	ipHeader := BuildIPv4Header(srcIP, dstIP, 17, len(udpData))
	return append(ipHeader, udpData...)
}
