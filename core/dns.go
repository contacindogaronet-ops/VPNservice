package core

import (
	"encoding/binary"
	"errors"
	"fmt"
	"net"
	"strings"
	"sync"
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
	1:   "A",
	2:   "NS",
	5:   "CNAME",
	6:   "SOA",
	12:  "PTR",
	15:  "MX",
	16:  "TXT",
	28:  "AAAA",
	33:  "SRV",
	65:  "HTTPS",
	255: "ANY",
}

// ParseDNSQuery bitwise RFC 1035 UDP parser.
func ParseDNSQuery(data []byte) (*DNSQueryInfo, error) {
	if len(data) < 12 {
		return nil, errors.New("dns payload truncated")
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
			ID: id, QR: qr, Opcode: opcode, AA: aa, TC: tc, RD: rd, RA: ra, RCode: rCode,
		}, nil
	}

	offset := 12
	qname, newOffset, err := parseDomainLabels(data, offset)
	if err != nil {
		return nil, err
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

	return &DNSQueryInfo{
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
	}, nil
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
			return "", 0, errors.New("pointer beyond bounds")
		}

		length := int(data[offset])
		if length == 0 {
			if !jumped {
				offset++
			}
			break
		}

		if (length & 0xC0) == 0xC0 {
			if offset+1 >= len(data) {
				return "", 0, errors.New("invalid compression pointer")
			}
			if jumpCount >= maxJumps {
				return "", 0, errors.New("excessive pointer jumps")
			}

			pointer := int(binary.BigEndian.Uint16(data[offset:offset+2]) & 0x3FFF)
			if visited[pointer] {
				return "", 0, errors.New("cyclic pointer loop")
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
			return "", 0, errors.New("label exceeds buffer")
		}

		labels = append(labels, string(data[offset:offset+length]))
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

// ------------------- FAKEDNS ENGINE -------------------

type FakeDNSEngine struct {
	mu          sync.RWMutex
	domainToIP  map[string]net.IP
	ipToDomain  map[string]string
	currentHost uint32
	minHost     uint32
	maxHost     uint32
}

var globalFakeDNS = NewFakeDNSEngine()

func NewFakeDNSEngine() *FakeDNSEngine {
	return &FakeDNSEngine{
		domainToIP:  make(map[string]net.IP),
		ipToDomain:  make(map[string]string),
		currentHost: 0xC6120002, // 198.18.0.2
		minHost:     0xC6120002,
		maxHost:     0xC613FFFE, // 198.19.255.254
	}
}

func (f *FakeDNSEngine) GetOrAllocateFakeIP(domain string) net.IP {
	f.mu.Lock()
	defer f.mu.Unlock()

	domain = strings.ToLower(strings.TrimSpace(domain))
	if ip, exists := f.domainToIP[domain]; exists {
		return ip
	}

	ipBytes := make([]byte, 4)
	binary.BigEndian.PutUint32(ipBytes, f.currentHost)
	fakeIP := net.IPv4(ipBytes[0], ipBytes[1], ipBytes[2], ipBytes[3])

	f.domainToIP[domain] = fakeIP
	f.ipToDomain[fakeIP.String()] = domain

	f.currentHost++
	if f.currentHost > f.maxHost {
		f.currentHost = f.minHost
	}

	return fakeIP
}

func (f *FakeDNSEngine) GetDomainByFakeIP(ip net.IP) (string, bool) {
	if ip == nil {
		return "", false
	}
	f.mu.RLock()
	defer f.mu.RUnlock()

	domain, exists := f.ipToDomain[ip.String()]
	return domain, exists
}

func BuildDNSResponseA(queryPayload []byte, queryID uint16, fakeIP net.IP) []byte {
	if len(queryPayload) < 12 {
		return nil
	}

	resp := make([]byte, len(queryPayload))
	copy(resp, queryPayload)

	resp[2] = 0x81
	resp[3] = 0x80
	resp[6] = 0x00
	resp[7] = 0x01

	answer := []byte{
		0xC0, 0x0C,
		0x00, 0x01,
		0x00, 0x01,
		0x00, 0x00, 0x00, 0x3C,
		0x00, 0x04,
	}
	answer = append(answer, fakeIP.To4()...)
	return append(resp, answer...)
}

func BuildDNSResponseEmpty(queryPayload []byte) []byte {
	if len(queryPayload) < 12 {
		return nil
	}
	resp := make([]byte, len(queryPayload))
	copy(resp, queryPayload)

	resp[2] = 0x81
	resp[3] = 0x80
	resp[6] = 0x00
	resp[7] = 0x00
	return resp
}
