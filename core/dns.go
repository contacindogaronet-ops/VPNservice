package core

import (
	"encoding/binary"
	"errors"
	"fmt"
	"strings"
)

type DNSQueryInfo struct {
	ID        uint16
	QR        bool
	Opcode    uint8
	AA        bool
	TC        bool
	RD        bool
	RA        bool
	RCode     uint8
	QDCount   uint16
	ANCount   uint16
	QName     string
	QType     uint16
	QTypeName string
	QClass    uint16
}

var typeMap = map[uint16]string{
	1:     "A",
	2:     "NS",
	5:     "CNAME",
	6:     "SOA",
	12:    "PTR",
	15:    "MX",
	16:    "TXT",
	28:    "AAAA",
	33:    "SRV",
	65:    "HTTPS",
	255:   "ANY",
}

// ParseDNSQuery performs a bitwise analysis of an RFC 1035 UDP DNS packet.
func ParseDNSQuery(data []byte) (*DNSQueryInfo, error) {
	if len(data) < 12 {
		return nil, errors.New("dns payload truncated: shorter than header size")
	}

	id := binary.BigEndian.Uint16(data[0:2])
	flags := binary.BigEndian.Uint16(data[2:4])
	qdCount := binary.BigEndian.Uint16(data[4:6])
	anCount := binary.BigEndian.Uint16(data[6:8])

	qr := ((flags >> 15) & 0x01) == 1
	opcode := uint8((flags >> 11) & 0x0F)
	aa := ((flags >> 10) & 0x01) == 1
	tc := ((flags >> 9) & 0x01) == 1
	rd := ((flags >> 8) & 0x01) == 1
	ra := ((flags >> 7) & 0x01) == 1
	rCode := uint8(flags & 0x0F)

	if qdCount == 0 {
		return &DNSQueryInfo{
			ID:      id,
			QR:      qr,
			Opcode:  opcode,
			AA:      aa,
			TC:      tc,
			RD:      rd,
			RA:      ra,
			RCode:   rCode,
			QDCount: qdCount,
			ANCount: anCount,
		}, nil
	}

	offset := 12
	qname, newOffset, err := parseDomainLabels(data, offset)
	if err != nil {
		return nil, fmt.Errorf("malformed DNS QNAME: %w", err)
	}
	offset = newOffset

	if len(data) < offset+4 {
		return nil, errors.New("dns question section incomplete")
	}

	qtype := binary.BigEndian.Uint16(data[offset : offset+2])
	qclass := binary.BigEndian.Uint16(data[offset+2 : offset+4])

	qtypeName, exists := typeMap[qtype]
	if !exists {
		qtypeName = fmt.Sprintf("TYPE-%d", qtype)
	}

	info := &DNSQueryInfo{
		ID:        id,
		QR:        qr,
		Opcode:    opcode,
		AA:        aa,
		TC:        tc,
		RD:        rd,
		RA:        ra,
		RCode:     rCode,
		QDCount:   qdCount,
		ANCount:   anCount,
		QName:     qname,
		QType:     qtype,
		QTypeName: qtypeName,
		QClass:    qclass,
	}

	return info, nil
}

func parseDomainLabels(data []byte, offset int) (string, int, error) {
	var labels []string
	visited := make(map[int]bool)
	initialOffset := offset
	jumped := false
	maxJumps := 10
	jumpCount := 0

	for {
		if offset >= len(data) {
			return "", 0, errors.New("pointer beyond packet bounds")
		}

		length := int(data[offset])

		if length == 0 {
			if !jumped {
				offset++
			}
			break
		}

		// Support RFC 1035 DNS decompression pointer (top 2 bits set)
		if (length & 0xC0) == 0xC0 {
			if offset+1 >= len(data) {
				return "", 0, errors.New("invalid compression pointer offset")
			}
			if jumpCount >= maxJumps {
				return "", 0, errors.New("excessive DNS pointer indirection")
			}

			pointer := int(binary.BigEndian.Uint16(data[offset:offset+2]) & 0x3FFF)
			if visited[pointer] {
				return "", 0, errors.New("cyclic DNS compression pointer loop")
			}
			visited[pointer] = true

			if !jumped {
				initialOffset = offset + 2
				jumped = true
			}
			offset = pointer
			jumpCount++
			continue
		}

		offset++
		if offset+length > len(data) {
			return "", 0, errors.New("label extends past buffer size")
		}

		label := string(data[offset : offset+length])
		labels = append(labels, label)
		offset += length

		if !jumped {
			initialOffset = offset
		}
	}

	domain := strings.Join(labels, ".")
	if jumped {
		return domain, initialOffset, nil
	}
	return domain, offset, nil
}
