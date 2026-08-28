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

// Dial creates an authenticated RFC 1928 SOCKS5 tunnel to target host:port.
func (r *SOCKS5Relay) Dial(ctx context.Context, targetHost string, targetPort uint16) (net.Conn, error) {
	dialer := net.Dialer{Timeout: r.Timeout}
	conn, err := dialer.DialContext(ctx, "tcp", r.ProxyAddress)
	if err != nil {
		return nil, fmt.Errorf("failed connecting to SOCKS5 upstream %s: %w", r.ProxyAddress, err)
	}

	// 1. Version identifier and method selection
	if _, err := conn.Write([]byte{0x05, 0x01, 0x00}); err != nil {
		_ = conn.Close()
		return nil, fmt.Errorf("socks5 handshake method write failed: %w", err)
	}

	resp := make([]byte, 2)
	if _, err := io.ReadFull(conn, resp); err != nil {
		_ = conn.Close()
		return nil, fmt.Errorf("socks5 handshake method read failed: %w", err)
	}

	if resp[0] != 0x05 || resp[1] != 0x00 {
		_ = conn.Close()
		return nil, fmt.Errorf("unsupported socks5 authentication: ver=%d auth=%d", resp[0], resp[1])
	}

	// 2. Transmit SOCKS5 Request
	reqBuf := make([]byte, 0, 260)
	reqBuf = append(reqBuf, 0x05, 0x01, 0x00) // VER=5, CMD=CONNECT, RSV=0

	ip := net.ParseIP(targetHost)
	if ip4 := ip.To4(); ip4 != nil {
		reqBuf = append(reqBuf, 0x01) // ATYP: IPv4
		reqBuf = append(reqBuf, ip4...)
	} else if ip6 := ip.To16(); ip6 != nil {
		reqBuf = append(reqBuf, 0x04) // ATYP: IPv6
		reqBuf = append(reqBuf, ip6...)
	} else {
		if len(targetHost) > 255 {
			_ = conn.Close()
			return nil, errors.New("target domain exceeds max RFC 1928 length (255)")
		}
		reqBuf = append(reqBuf, 0x03) // ATYP: Domain name
		reqBuf = append(reqBuf, byte(len(targetHost)))
		reqBuf = append(reqBuf, targetHost...)
	}

	portBytes := make([]byte, 2)
	binary.BigEndian.PutUint16(portBytes, targetPort)
	reqBuf = append(reqBuf, portBytes...)

	if _, err := conn.Write(reqBuf); err != nil {
		_ = conn.Close()
		return nil, fmt.Errorf("socks5 connect request transmission error: %w", err)
	}

	// 3. Receive SOCKS5 Server Response
	replyHeader := make([]byte, 4)
	if _, err := io.ReadFull(conn, replyHeader); err != nil {
		_ = conn.Close()
		return nil, fmt.Errorf("socks5 connect reply read error: %w", err)
	}

	if replyHeader[1] != 0x00 {
		_ = conn.Close()
		return nil, fmt.Errorf("socks5 connection rejected with status code: 0x%02X", replyHeader[1])
	}

	// Drain BND.ADDR and BND.PORT
	var bndAddrLen int
	switch replyHeader[3] {
	case 0x01: // IPv4
		bndAddrLen = 4
	case 0x04: // IPv6
		bndAddrLen = 16
	case 0x03: // Domain
		domainLen := make([]byte, 1)
		if _, err := io.ReadFull(conn, domainLen); err != nil {
			_ = conn.Close()
			return nil, err
		}
		bndAddrLen = int(domainLen[0])
	default:
		_ = conn.Close()
		return nil, fmt.Errorf("unknown ATYP in reply: 0x%02X", replyHeader[3])
	}

	discardBuf := make([]byte, bndAddrLen+2)
	if _, err := io.ReadFull(conn, discardBuf); err != nil {
		_ = conn.Close()
		return nil, err
	}

	return conn, nil
}

// Splice conducts bidirectional zero-allocation data transfer with aggressive socket teardown.
func (r *SOCKS5Relay) Splice(local net.Conn, remote net.Conn) {
	var wg sync.WaitGroup
	wg.Add(2)

	pipe := func(dst net.Conn, src net.Conn, direction string) {
		defer wg.Done()
		bufPtr := bufferPool.Get().(*[]byte)
		defer bufferPool.Put(bufPtr)

		buf := *bufPtr
		for {
			nr, readErr := src.Read(buf)
			if nr > 0 {
				nw, writeErr := dst.Write(buf[0:nr])
				if writeErr != nil {
					break
				}
				if nr != nw {
					break
				}
			}
			if readErr != nil {
				break
			}
		}

		if tcpConn, ok := dst.(*net.TCPConn); ok {
			_ = tcpConn.CloseWrite()
		} else {
			_ = dst.Close()
		}
	}

	go pipe(remote, local, "Uplink")
	go pipe(local, remote, "Downlink")

	wg.Wait()
	_ = local.Close()
	_ = remote.Close()
}
