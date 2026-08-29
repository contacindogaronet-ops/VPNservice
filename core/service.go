package core

import (
	"context"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"os"
	"sync"
	"sync/atomic"
	"syscall"
)

const (
	MaxConcurrentTCP = 128
)

type TCPSession struct {
	clientIP   net.IP
	serverIP   net.IP
	clientPort uint16
	serverPort uint16
	clientSeq  uint32
	serverSeq  uint32
	outConn    net.Conn
	ctx        context.Context
	cancel     context.CancelFunc
	closed     atomic.Bool
}

type VpnEngine struct {
	tunFd         int
	tunFile       *os.File
	localListen   string
	dnsAddr       string
	ctx           context.Context
	cancel        context.CancelFunc
	wg            sync.WaitGroup
	running       atomic.Bool
	writeMu       sync.Mutex
	sessionMu     sync.RWMutex
	tcpSession    map[string]*TCPSession
	semaphore     chan struct{}
	socksListener net.Listener
}

var globalEngine *VpnEngine
var engineMutex sync.Mutex

// StartEngine menyalakan Monolithic Kernel & SOCKS5 listener internal pada 127.0.0.3:2007.
func StartEngine(fd int, localSocksAddr string, dnsAddr string) bool {
	engineMutex.Lock()
	defer engineMutex.Unlock()

	if globalEngine != nil && globalEngine.running.Load() {
		return true
	}

	if localSocksAddr == "" {
		localSocksAddr = "127.0.0.3:2007"
	}
	if dnsAddr == "" {
		dnsAddr = "1.1.1.1"
	}

	ctx, cancel := context.WithCancel(context.Background())
	engine := &VpnEngine{
		tunFd:       fd,
		localListen: localSocksAddr,
		dnsAddr:     dnsAddr,
		ctx:         ctx,
		cancel:      cancel,
		tcpSession:  make(map[string]*TCPSession),
		semaphore:   make(chan struct{}, MaxConcurrentTCP),
	}

	engine.tunFile = os.NewFile(uintptr(fd), "tun")
	if engine.tunFile == nil {
		AddLog("ERROR", "Failed opening native TUN file.")
		cancel()
		return false
	}

	go engine.startEmbeddedSocks5(localSocksAddr)

	engine.running.Store(true)
	globalEngine = engine

	AddLog("INFO", fmt.Sprintf("Neural Monolith Online. Internal Engine: %s", localSocksAddr))

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

	AddLog("INFO", "Stopping Monolith Engine...")
	globalEngine.running.Store(false)
	globalEngine.cancel()

	if globalEngine.socksListener != nil {
		_ = globalEngine.socksListener.Close()
	}

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
	AddLog("INFO", "Monolith Engine stopped cleanly.")
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

func (e *VpnEngine) startEmbeddedSocks5(listenAddr string) {
	var lc net.ListenConfig
	l, err := lc.Listen(e.ctx, "tcp4", listenAddr)
	if err != nil {
		AddLog("WARN", fmt.Sprintf("Embedded SOCKS5 bind note: %v", err))
		return
	}
	e.socksListener = l

	for {
		conn, err := l.Accept()
		if err != nil {
			return
		}
		go e.handleSocks5Client(conn)
	}
}

func (e *VpnEngine) handleSocks5Client(client net.Conn) {
	defer client.Close()

	// Handshake
	buf := make([]byte, 256)
	if _, err := io.ReadFull(client, buf[:2]); err != nil || buf[0] != 0x05 {
		return
	}
	nMethods := int(buf[1])
	if _, err := io.ReadFull(client, buf[:nMethods]); err != nil {
		return
	}
	if _, err := client.Write([]byte{0x05, 0x00}); err != nil {
		return
	}

	// Request
	if _, err := io.ReadFull(client, buf[:4]); err != nil || buf[1] != 0x01 {
		return
	}

	var targetAddr string
	switch buf[3] {
	case 0x01: // IPv4
		if _, err := io.ReadFull(client, buf[:4]); err != nil {
			return
		}
		targetAddr = net.IP(buf[:4]).String()
	case 0x03: // Domain
		if _, err := io.ReadFull(client, buf[:1]); err != nil {
			return
		}
		dLen := int(buf[0])
		if _, err := io.ReadFull(client, buf[:dLen]); err != nil {
			return
		}
		targetAddr = string(buf[:dLen])
	default:
		return
	}

	portBuf := make([]byte, 2)
	if _, err := io.ReadFull(client, portBuf); err != nil {
		return
	}
	targetPort := binary.BigEndian.Uint16(portBuf)
	fullTarget := fmt.Sprintf("%s:%d", targetAddr, targetPort)

	dialer := ProtectedDialer(5)
	remote, err := dialer.Dial("tcp", fullTarget)
	if err != nil {
		_, _ = client.Write([]byte{0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}
	defer remote.Close()

	if _, err := client.Write([]byte{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0}); err != nil {
		return
	}

	var wg sync.WaitGroup
	wg.Add(2)
	pipe := func(dst, src net.Conn) {
		defer wg.Done()
		bufPtr := bufferPool.Get().(*[]byte)
		defer bufferPool.Put(bufPtr)
		_, _ = io.CopyBuffer(dst, src, *bufPtr)
		_ = dst.Close()
	}
	go pipe(remote, client)
	go pipe(client, remote)
	wg.Wait()
}

func isIgnoredPacket(dstIP net.IP, dstPort uint16) bool {
	if dstIP == nil || dstIP.IsLoopback() || dstIP.IsMulticast() || dstIP.IsUnspecified() {
		return true
	}
	if dstIP.Equal(net.ParseIP("10.10.0.2")) || dstIP.Equal(net.ParseIP("255.255.255.255")) {
		return true
	}
	switch dstPort {
	case 853, 123, 1900, 5353, 137, 138:
		return true
	}
	return false
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
			continue
		}

		ihl := int(packet[0]&0x0F) * 4
		if n < ihl {
			continue
		}

		protocol := packet[9]
		srcIP := net.IP(packet[12:16])
		dstIP := net.IP(packet[16:20])

		if isIgnoredPacket(dstIP, 0) {
			continue
		}

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
				e.handleDNS(srcIP, dstIP, srcPort, payload)
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

			if isIgnoredPacket(dstIP, dstPort) {
				if (flags & 0x02) != 0 {
					rstPkt := BuildTCPPacket(dstIP, srcIP, dstPort, srcPort, 0, seq+1, 0x14, nil)
					e.writeToTun(rstPkt)
				}
				continue
			}

			var payload []byte
			if n > ihl+dataOffset {
				payload = packet[ihl+dataOffset:]
			}

			e.handleTCP(srcIP, dstIP, srcPort, dstPort, seq, ack, flags, payload)
		}
	}
}

func (e *VpnEngine) handleDNS(clientIP, serverIP net.IP, clientPort uint16, dnsPayload []byte) {
	dnsInfo, err := ParseDNSQuery(dnsPayload)
	if err != nil || dnsInfo == nil || dnsInfo.QName == "" {
		return
	}

	var responseData []byte

	if dnsInfo.QType == 1 { // A Record
		fakeIP := globalFakeDNS.GetOrAllocateFakeIP(dnsInfo.QName)
		responseData = BuildDNSResponseA(dnsPayload, dnsInfo.ID, fakeIP)
	} else {
		responseData = BuildDNSResponseEmpty(dnsPayload)
	}

	if responseData != nil {
		udpPkt := BuildUDPPacket(serverIP, clientIP, 53, clientPort, responseData)
		e.writeToTun(udpPkt)
	}
}

func (e *VpnEngine) handleTCP(srcIP, dstIP net.IP, srcPort, dstPort uint16, seq, ack uint32, flags byte, payload []byte) {
	sessionKey := fmt.Sprintf("%s:%d->%s:%d", srcIP, srcPort, dstIP, dstPort)

	isSyn := (flags & 0x02) != 0
	isFin := (flags & 0x01) != 0
	isRst := (flags & 0x04) != 0
	isAck := (flags & 0x10) != 0

	if isSyn {
		e.sessionMu.Lock()
		if existing, ok := e.tcpSession[sessionKey]; ok && !existing.closed.Load() {
			e.sessionMu.Unlock()
			return
		}

		select {
		case e.semaphore <- struct{}{}:
		default:
			e.sessionMu.Unlock()
			rstPkt := BuildTCPPacket(dstIP, srcIP, dstPort, srcPort, 0, seq+1, 0x14, nil)
			e.writeToTun(rstPkt)
			return
		}

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
		e.tcpSession[sessionKey] = sess
		e.sessionMu.Unlock()

		targetHost := dstIP.String()
		if domain, isFake := globalFakeDNS.GetDomainByFakeIP(dstIP); isFake {
			targetHost = domain
		}

		go func() {
			defer func() { <-e.semaphore }()

			dialer := ProtectedDialer(4)
			outConn, err := dialer.DialContext(ctx, "tcp", fmt.Sprintf("%s:%d", targetHost, dstPort))
			if err != nil {
				e.sessionMu.Lock()
				delete(e.tcpSession, sessionKey)
				e.sessionMu.Unlock()
				sess.close()

				rstPkt := BuildTCPPacket(dstIP, srcIP, dstPort, srcPort, 0, seq+1, 0x14, nil)
				e.writeToTun(rstPkt)
				return
			}

			sess.outConn = outConn
			AddLog("TCP", fmt.Sprintf("ONLINE -> %s:%d", targetHost, dstPort))

			synAckPkt := BuildTCPPacket(dstIP, srcIP, dstPort, srcPort, sess.serverSeq, sess.clientSeq, 0x12, nil)
			sess.serverSeq++
			e.writeToTun(synAckPkt)

			go sess.forwardDownstream(e, sessionKey)
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
		e.sessionMu.Lock()
		delete(e.tcpSession, sessionKey)
		e.sessionMu.Unlock()
		sess.close()

		finAck := BuildTCPPacket(dstIP, srcIP, dstPort, srcPort, sess.serverSeq, seq+1, 0x11, nil)
		e.writeToTun(finAck)
		return
	}

	if isAck && len(payload) > 0 && sess.outConn != nil {
		sess.clientSeq = seq + uint32(len(payload))
		_, _ = sess.outConn.Write(payload)

		ackPkt := BuildTCPPacket(dstIP, srcIP, dstPort, srcPort, sess.serverSeq, sess.clientSeq, 0x10, nil)
		e.writeToTun(ackPkt)
	}
}

func (s *TCPSession) forwardDownstream(engine *VpnEngine, sessionKey string) {
	defer func() {
		engine.sessionMu.Lock()
		delete(engine.tcpSession, sessionKey)
		engine.sessionMu.Unlock()
		s.close()
	}()

	bufPtr := bufferPool.Get().(*[]byte)
	defer bufferPool.Put(bufPtr)
	buf := *bufPtr

	for {
		select {
		case <-s.ctx.Done():
			return
		default:
		}

		if s.outConn == nil {
			return
		}

		n, err := s.outConn.Read(buf)
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
		if s.outConn != nil {
			_ = s.outConn.Close()
		}
	}
}
