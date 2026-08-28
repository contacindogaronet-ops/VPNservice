package core

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"sync"
	"time"
)

const BufferSize = 32 * 1024

var bufferPool = sync.Pool{
	New: func() any {
		b := make([]byte, BufferSize)
		return &b
	},
}

type SOCKS5Relay struct {
	ProxyAddress string
	Timeout      time.Duration
}

func NewSOCKS5Relay(proxyAddress string, timeout time.Duration) *SOCKS5Relay {
	return &SOCKS5Relay{
		ProxyAddress: proxyAddress,
		Timeout:      timeout,
	}
}

// Dial establishes an authenticated SOCKS5 tunnel using ProtectedDialer to avoid TUN loops.
func (r *SOCKS5Relay) Dial(ctx context.Context, targetHost string, targetPort uint16) (net.Conn, error) {
	dialer := ProtectedDialer(int(r.Timeout.Seconds()))
	conn, err := dialer.DialContext(ctx, "tcp", r.ProxyAddress)
	if err != nil {
		return nil, fmt.Errorf("connect to upstream %s failed: %w", r.ProxyAddress, err)
	}

	// 1. Handshake
	if _, err := conn.Write([]byte{0x05, 0x01, 0x00}); err != nil {
		_ = conn.Close()
		return nil, fmt.Errorf("socks5 handshake write failed: %w", err)
	}

	resp := make([]byte, 2)
	if _, err := io.ReadFull(conn, resp); err != nil {
		_ = conn.Close()
		return nil, fmt.Errorf("socks5 handshake read failed: %w", err)
	}

	if resp[0] != 0x05 || resp[1] != 0x00 {
		_ = conn.Close()
		return nil, fmt.Errorf("unsupported socks5 authentication: 0x%02X", resp[1])
	}

	// 2. Request
	reqBuf := make([]byte, 0, 260)
	reqBuf = append(reqBuf, 0x05, 0x01, 0x00) // VER=5, CMD=CONNECT, RSV=0

	ip := net.ParseIP(targetHost)
	if ip4 := ip.To4(); ip4 != nil {
		reqBuf = append(reqBuf, 0x01)
		reqBuf = append(reqBuf, ip4...)
	} else if ip6 := ip.To16(); ip6 != nil {
		reqBuf = append(reqBuf, 0x04)
		reqBuf = append(reqBuf, ip6...)
	} else {
		if len(targetHost) > 255 {
			_ = conn.Close()
			return nil, errors.New("domain name exceeds 255 chars")
		}
		reqBuf = append(reqBuf, 0x03, byte(len(targetHost)))
		reqBuf = append(reqBuf, targetHost...)
	}

	portBytes := make([]byte, 2)
	binary.BigEndian.PutUint16(portBytes, targetPort)
	reqBuf = append(reqBuf, portBytes...)

	if _, err := conn.Write(reqBuf); err != nil {
		_ = conn.Close()
		return nil, fmt.Errorf("socks5 request write failed: %w", err)
	}

	// 3. Response
	replyHeader := make([]byte, 4)
	if _, err := io.ReadFull(conn, replyHeader); err != nil {
		_ = conn.Close()
		return nil, fmt.Errorf("socks5 reply read failed: %w", err)
	}

	if replyHeader[1] != 0x00 {
		_ = conn.Close()
		return nil, fmt.Errorf("socks5 rejected connection with status: 0x%02X", replyHeader[1])
	}

	var bndAddrLen int
	switch replyHeader[3] {
	case 0x01:
		bndAddrLen = 4
	case 0x04:
		bndAddrLen = 16
	case 0x03:
		domainLen := make([]byte, 1)
		if _, err := io.ReadFull(conn, domainLen); err != nil {
			_ = conn.Close()
			return nil, err
		}
		bndAddrLen = int(domainLen[0])
	default:
		_ = conn.Close()
		return nil, fmt.Errorf("unknown ATYP: 0x%02X", replyHeader[3])
	}

	discardBuf := make([]byte, bndAddrLen+2)
	if _, err := io.ReadFull(conn, discardBuf); err != nil {
		_ = conn.Close()
		return nil, err
	}

	return conn, nil
}
