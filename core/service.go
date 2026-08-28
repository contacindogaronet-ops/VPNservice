package core

import (
	"context"
	"encoding/binary"
	"fmt"
	"net"
	"os"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
)

type VpnEngine struct {
	tunFd     int
	tunFile   *os.File
	socksAddr string
	dnsAddr   string
	relay     *SOCKS5Relay
	ctx       context.Context
	cancel    context.CancelFunc
	wg        sync.WaitGroup
	running   atomic.Bool
}

var globalEngine *VpnEngine
var engineMutex sync.Mutex

// StartEngine launches the monolithic kernel loop with the TUN file descriptor.
func StartEngine(fd int, socksAddr string, dnsAddr string) bool {
	engineMutex.Lock()
	defer engineMutex.Unlock()

	if globalEngine != nil && globalEngine.running.Load() {
		AddLog("WARN", "VPN Kernel is already running")
		return true
	}

	ctx, cancel := context.WithCancel(context.Background())
	engine := &VpnEngine{
		tunFd:     fd,
		socksAddr: socksAddr,
		dnsAddr:   dnsAddr,
		relay:     NewSOCKS5Relay(socksAddr, 10*time.Second),
		ctx:       ctx,
		cancel:    cancel,
	}

	engine.tunFile = os.NewFile(uintptr(fd), "tun")
	if engine.tunFile == nil {
		AddLog("ERROR", "Invalid TUN file descriptor received from VpnService")
		cancel()
		return false
	}

	engine.running.Store(true)
	globalEngine = engine

	AddLog("INFO", fmt.Sprintf("Kernel Engine initialized on FD %d. SOCKS5: %s, DNS: %s", fd, socksAddr, dnsAddr))

	engine.wg.Add(1)
	go engine.readLoop()

	return true
}

// StopEngine tears down the TUN loop and releases kernel descriptors.
func StopEngine() {
	engineMutex.Lock()
	defer engineMutex.Unlock()

	if globalEngine == nil || !globalEngine.running.Load() {
		return
	}

	AddLog("INFO", "Initiating VPN Kernel engine shutdown sequence")
	globalEngine.running.Store(false)
	globalEngine.cancel()

	if globalEngine.tunFile != nil {
		_ = globalEngine.tunFile.Close()
	}
	_ = syscall.Close(globalEngine.tunFd)

	globalEngine.wg.Wait()
	globalEngine = nil
	AddLog("INFO", "VPN Kernel stopped completely. Clean state achieved.")
}

// IsRunning reports engine activity state to UI bridge.
func IsRunning() bool {
	engineMutex.Lock()
	defer engineMutex.Unlock()
	return globalEngine != nil && globalEngine.running.Load()
}

func (e *VpnEngine) readLoop() {
	defer e.wg.Done()
	defer func() {
		if r := recover(); r != nil {
			AddLog("ERROR", fmt.Sprintf("Fatal crash in TUN readLoop recovered: %v", r))
		}
	}()

	buf := make([]byte, 65535)

	for {
		select {
		case <-e.ctx.Done():
			return
		default:
		}

		n, err := e.tunFile.Read(buf)
		if err != nil {
			if e.running.Load() {
				AddLog("ERROR", fmt.Sprintf("TUN device read failure: %v", err))
			}
			return
		}

		if n < 20 {
			continue
		}

		packet := buf[:n]
		version := packet[0] >> 4
		if version != 4 {
			// Skip non-IPv4 traffic
			continue
		}

		ihl := int(packet[0]&0x0F) * 4
		if ihl < 20 || n < ihl {
			continue
		}

		protocol := packet[9]
		srcIP := net.IP(packet[12:16])
		dstIP := net.IP(packet[16:20])

		switch protocol {
		case 17: // UDP
			if n < ihl+8 {
				continue
			}
			udpHeader := packet[ihl : ihl+8]
			srcPort := binary.BigEndian.Uint16(udpHeader[0:2])
			dstPort := binary.BigEndian.Uint16(udpHeader[2:4])
			udpLen := binary.BigEndian.Uint16(udpHeader[4:6])

			if int(udpLen) < 8 || n < ihl+int(udpLen) {
				continue
			}
			udpPayload := packet[ihl+8 : ihl+int(udpLen)]

			if dstPort == 53 || srcPort == 53 {
				dnsInfo, err := ParseDNSQuery(udpPayload)
				if err == nil && dnsInfo != nil {
					action := MatchDomain(dnsInfo.QName)
					AddLog("DNS", fmt.Sprintf("Query: %s (%s) [Action: %s]", dnsInfo.QName, dnsInfo.QTypeName, action))
				}
			} else {
				AddLog("DEBUG", fmt.Sprintf("UDP: %s:%d -> %s:%d (Bytes: %d)", srcIP, srcPort, dstIP, dstPort, len(udpPayload)))
			}

		case 6: // TCP
			if n < ihl+20 {
				continue
			}
			tcpHeader := packet[ihl : ihl+20]
			srcPort := binary.BigEndian.Uint16(tcpHeader[0:2])
			dstPort := binary.BigEndian.Uint16(tcpHeader[2:4])
			flags := tcpHeader[13]

			isSyn := (flags & 0x02) != 0
			if isSyn {
				action := MatchIP(dstIP.String())
				AddLog("TCP", fmt.Sprintf("CONNECT %s:%d -> %s:%d [Action: %s]", srcIP, srcPort, dstIP, dstPort, action))
			}

		case 1: // ICMP
			AddLog("DEBUG", fmt.Sprintf("ICMP Echo packet from %s to %s", srcIP, dstIP))
		}
	}
}

