package core

import (
	"context"
	"net"
	"strings"
	"syscall"
)

// SocketProtector is implemented on the Java side by NeuralVpnService.
type SocketProtector interface {
	Protect(fd int) bool
}

var globalProtector SocketProtector

// RegisterSocketProtector binds Android VpnService.protect() to Go runtime.
func RegisterSocketProtector(p SocketProtector) {
	globalProtector = p
}

// isLoopback checks if the address is a local loopback (Termux / Localhost).
func isLoopback(address string) bool {
	host, _, err := net.SplitHostPort(address)
	if err != nil {
		host = address
	}
	host = strings.TrimSpace(strings.ToLower(host))
	if host == "localhost" || host == "127.0.0.1" || strings.HasPrefix(host, "127.") || host == "::1" {
		return true
	}
	ip := net.ParseIP(host)
	if ip != nil {
		return ip.IsLoopback()
	}
	return false
}

// ProtectedDialer creates outbound TCP connections, protecting remote sockets and letting loopback connect directly to lo.
func ProtectedDialer(timeoutSec int) *net.Dialer {
	return &net.Dialer{
		Control: func(network, address string, c syscall.RawConn) error {
			// JANGAN protect alamat loopback (127.0.0.1), biarkan melewati interface lo!
			if isLoopback(address) {
				return nil
			}
			return c.Control(func(fd uintptr) {
				if globalProtector != nil {
					globalProtector.Protect(int(fd))
				}
			})
		},
	}
}

// ProtectedListenUDP creates a protected UDP socket for DNS queries.
func ProtectedListenUDP(ctx context.Context) (*net.UDPConn, error) {
	lc := net.ListenConfig{
		Control: func(network, address string, c syscall.RawConn) error {
			if isLoopback(address) {
				return nil
			}
			return c.Control(func(fd uintptr) {
				if globalProtector != nil {
					globalProtector.Protect(int(fd))
				}
			})
		},
	}
	conn, err := lc.ListenPacket(ctx, "udp4", ":0")
	if err != nil {
		return nil, err
	}
	return conn.(*net.UDPConn), nil
}
