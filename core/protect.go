package core

import (
	"context"
	"net"
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

// ProtectedDialer creates outbound TCP connections that bypass the VPN TUN.
func ProtectedDialer(timeoutSec int) *net.Dialer {
	return &net.Dialer{
		Control: func(network, address string, c syscall.RawConn) error {
			return c.Control(func(fd uintptr) {
				if globalProtector != nil {
					globalProtector.Protect(int(fd))
				}
			})
		},
	}
}

// ProtectedListenUDP creates a UDP socket protected from VPN loops.
func ProtectedListenUDP(ctx context.Context) (*net.UDPConn, error) {
	lc := net.ListenConfig{
		Control: func(network, address string, c syscall.RawConn) error {
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
