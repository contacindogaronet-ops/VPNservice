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

type TCPSession struct {
	clientIP   net.IP
	serverIP   net.IP
	clientPort uint16
	serverPort uint16
	clientSeq  uint32
	serverSeq  uint32
	socksConn  net.Conn
	ctx        context.Context
	cancel     context.CancelFunc
	closed     atomic.Bool
}

type VpnEngine struct {
	tunFd      int
	tunFile    *os.File
	socksAddr  string
	dnsAddr    string
	relay      *SOCKS5Relay
	ctx        context.Context
	cancel     context.CancelFunc
	wg         sync.WaitGroup
	running    atomic.Bool
	writeMu    sync.Mutex
	sessionMu  sync.RWMutex
	tcpSession map[string]*TCPSession
}

var globalEngine *VpnEngine
var engineMutex sync.Mutex

func StartEngine(fd int, socksAddr string, dnsAddr string) bool {
	engineMutex.Lock()
	defer engineMutex.Unlock()

	if globalEngine != nil && globalEngine.running.Load() {
		AddLog("WARN", "VPN Kernel already active.")
		return true
	}

	ctx, cancel := context.WithCancel(context.Background())
	engine := &VpnEngine{
		tunFd:      fd,
		socksAddr:  socksAddr,
		dnsAddr:    dnsAddr,
		relay:      NewSOCKS5Relay(socksAddr, 10*time.Second),
		ctx:        ctx,
		cancel:     cancel,
		tcpSession: make(map[string]*TCPSession),
	}

	engine.tunFile = os.NewFile(uintptr(fd), "tun")
	if engine.tunFile == nil {
		AddLog("ERROR", "Invalid TUN descriptor.")
		cancel()
		return false
	}

	engine.running.Store(true)
	globalEngine = engine

	AddLog("INFO", fmt.Sprintf("Kernel active on FD %d. Upstream: %s, DNS: %s", fd, socksAddr, dnsAddr))

	engine.wg.Add(1)
	go engine.readLoop()

	return true
}

func StopEngine() {
	engineMutex.Lock()
	defer engineMutex.Unlock()

	if globalEngine == nil || !globalEngine.running.Load() {
		return
	}

	AddLog("INFO", "Stopping VPN Kernel Engine...")
	globalEngine.running.Store(false)
	globalEngine.cancel()

	globalEngine.sessionMu.Lock()
	for _, sess := range globalEngine.tcpSession {
		sess.close()
	}
	globalEngine.tcpSession = make(map[string]*TCPSession)
	globalEngine.sessionMu.Unlock()

	if globalEngine.tunFile != nil {
		_ = globalEngine.tunFile.Close()
	}
	_ = syscall.Close(globalEngine.tunFd)

	globalEngine.wg.Wait()
	globalEngine = nil
	AddLog("INFO", "VPN Kernel shutdown complete.")
}

func IsRunning() bool {
	engineMutex.Lock()
	defer engineMutex.Unlock()
	return globalEngine != nil && globalEngine.running.Load()
}

func (e *VpnEngine) writeToTun(packet []byte) {
	e.writeMu.Lock()
	defer e.writeMu.Unlock()
	if e.tunFile != nil && e.running.Load() {
		_, _ = e.tunFile.Write(packet)
	}
}

func (e *VpnEngine) readLoop() {
	defer e.wg.Done()
	buf := make([]byte, 65535)

	for {
		select {
		case <-e.ctx.Done():
			return
		default:
		}

		n, err := e.tunFile.Read(buf)
		if err != nil {
			return
		}
		if n < 20 {
			continue
		}

		packet := buf[:n]
		if packet[0]>>4 != 4 {
			continue // IPv4 only
		}

		ihl := int(packet[0]&0x0F) * 4
		if n < ihl {
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
			payload := packet[ihl+8 : ihl+int(udpLen)]

			if dstPort == 53 {
				go e.handleDNS(srcIP, dstIP, srcPort, payload)
			}

		case 6: // TCP
			if n < ihl+20 {
				continue
			}
			tcpHeader := packet[ihl : ihl+20]
			srcPort := binary.BigEndian.Uint16(tcpHeader[0:2])
			dstPort := binary.BigEndian.Uint16(tcpHeader[2:4])
			seq := binary.BigEndian.Uint32(tcpHeader[4:8])
			ack := binary.BigEndian.Uint32(tcpHeader[8:12])
			dataOffset := int(tcpHeader[12]>>4) * 4
			flags := tcpHeader[13]

			var payload []byte
			if n > ihl+dataOffset {
				payload = packet[ihl+dataOffset:]
			}

			e.handleTCP(srcIP, dstIP, srcPort, dstPort, seq, ack, flags, payload)
		}
	}
}

func (e *VpnEngine) handleDNS(clientIP, serverIP net.IP, clientPort uint16, dnsPayload []byte) {
	conn, err := ProtectedListenUDP(e.ctx)
	if err != nil {
		return
	}
	defer conn.Close()

	rAddr, err := net.ResolveUDPAddr("udp", e.dnsAddr+":53")
	if err != nil {
		return
	}

	_ = conn.SetDeadline(time.Now().Add(3 * time.Second))
	if _, err := conn.WriteTo(dnsPayload, rAddr); err != nil {
		return
	}

	respBuf := make([]byte, 2048)
	nr, _, err := conn.ReadFrom(respBuf)
	if err != nil || nr == 0 {
		return
	}

	dnsResp := respBuf[:nr]
	responsePkt := BuildUDPPacket(serverIP, clientIP, 53, clientPort, dnsResp)
	e.writeToTun(responsePkt)
}

func (e *VpnEngine) handleTCP(srcIP, dstIP net.IP, srcPort, dstPort uint16, seq, ack uint32, flags byte, payload []byte) {
	sessionKey := fmt.Sprintf("%s:%d->%s:%d", srcIP, srcPort, dstIP, dstPort)

	isSyn := (flags & 0x02) != 0
	isFin := (flags & 0x01) != 0
	isRst := (flags & 0x04) != 0
	isAck := (flags & 0x10) != 0

	if isSyn {
		// Start TCP Session
		ctx, cancel := context.WithCancel(e.ctx)
		sess := &TCPSession{
			clientIP:   srcIP,
			serverIP:   dstIP,
			clientPort: srcPort,
			serverPort: dstPort,
			clientSeq:  seq + 1,
			serverSeq:  1000,
			ctx:        ctx,
			cancel:     cancel,
		}

		e.sessionMu.Lock()
		e.tcpSession[sessionKey] = sess
		e.sessionMu.Unlock()

		go func() {
			socksConn, err := e.relay.Dial(ctx, dstIP.String(), dstPort)
			if err != nil {
				AddLog("ERROR", fmt.Sprintf("SOCKS5 Connect Failed: %v", err))
				rstPkt := BuildTCPPacket(dstIP, srcIP, dstPort, srcPort, 0, seq+1, 0x14, nil)
				e.writeToTun(rstPkt)
				sess.close()
				return
			}

			sess.socksConn = socksConn
			AddLog("TCP", fmt.Sprintf("Tunnel ESTABLISHED: %s:%d -> %s:%d", srcIP, srcPort, dstIP, dstPort))

			// Reply SYN-ACK to Android OS
			synAckPkt := BuildTCPPacket(dstIP, srcIP, dstPort, srcPort, sess.serverSeq, sess.clientSeq, 0x12, nil)
			sess.serverSeq++
			e.writeToTun(synAckPkt)

			// Read response from SOCKS5 and push back to TUN
			go sess.forwardDownstream(e)
		}()
		return
	}

	e.sessionMu.RLock()
	sess, exists := e.tcpSession[sessionKey]
	e.sessionMu.RUnlock()

	if !exists || sess.closed.Load() {
		if !isRst {
			rstPkt := BuildTCPPacket(dstIP, srcIP, dstPort, srcPort, ack, seq+1, 0x14, nil)
			e.writeToTun(rstPkt)
		}
		return
	}

	if isRst || isFin {
		sess.close()
		finAck := BuildTCPPacket(dstIP, srcIP, dstPort, srcPort, sess.serverSeq, seq+1, 0x11, nil)
		e.writeToTun(finAck)
		return
	}

	if isAck && len(payload) > 0 && sess.socksConn != nil {
		sess.clientSeq = seq + uint32(len(payload))
		_, _ = sess.socksConn.Write(payload)

		// Ack received payload
		ackPkt := BuildTCPPacket(dstIP, srcIP, dstPort, srcPort, sess.serverSeq, sess.clientSeq, 0x10, nil)
		e.writeToTun(ackPkt)
	}
}

func (s *TCPSession) forwardDownstream(engine *VpnEngine) {
	defer s.close()
	bufPtr := bufferPool.Get().(*[]byte)
	defer bufferPool.Put(bufPtr)
	buf := *bufPtr

	for {
		select {
		case <-s.ctx.Done():
			return
		default:
		}

		if s.socksConn == nil {
			return
		}

		n, err := s.socksConn.Read(buf)
		if err != nil || n == 0 {
			finPkt := BuildTCPPacket(s.serverIP, s.clientIP, s.serverPort, s.clientPort, s.serverSeq, s.clientSeq, 0x11, nil)
			engine.writeToTun(finPkt)
			return
		}

		chunkSize := 1400
		for offset := 0; offset < n; offset += chunkSize {
			end := offset + chunkSize
			if end > n {
				end = n
			}
			chunk := buf[offset:end]

			dataPkt := BuildTCPPacket(s.serverIP, s.clientIP, s.serverPort, s.clientPort, s.serverSeq, s.clientSeq, 0x18, chunk)
			s.serverSeq += uint32(len(chunk))
			engine.writeToTun(dataPkt)
		}
	}
}

func (s *TCPSession) close() {
	if s.closed.CompareAndSwap(false, true) {
		s.cancel()
		if s.socksConn != nil {
			_ = s.socksConn.Close()
		}
	}
}
