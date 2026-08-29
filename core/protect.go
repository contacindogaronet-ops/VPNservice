package core

import (
	"context"
	"net"
	"strings"
	"syscall"
	"time"
)

type SocketProtector interface {
	Protect(fd int) bool
}

var globalProtector SocketProtector

func RegisterSocketProtector(p SocketProtector) {
	globalProtector = p
}

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

func ProtectedDialer(timeoutSec int) *net.Dialer {
	if timeoutSec <= 0 {
		timeoutSec = 5
	}
	return &net.Dialer{
		Timeout: time.Duration(timeoutSec) * time.Second,
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
}

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
