package core

import (
	"io"
	"net"
	"sync"
	"github.com/rs/zerolog/log"
)

// 🔴 KUNCI ARSITEKTUR: Memory Pool Ganda untuk efisiensi ekstrem
var relayPool32 = sync.Pool{
	New: func() interface{} {
		b := make([]byte, 32*1024) // 32KB (Reguler / Anti-Jitter)
		return &b
	},
}

var vvipPool4M = sync.Pool{
	New: func() interface{} {
		b := make([]byte, 4*1024*1024) // 4MB (VVIP / MTProto)
		return &b
	},
}

// HandleSOCKS5 mematuhi RFC 1928 (Wajib kirim reply SUCCESS sebelum stream relay)
func HandleSOCKS5(client net.Conn, targetAddr string, isVVIP bool) {
	// RFC 1928 SOCKS5 Success Reply Matrix (0x00 = Succeeded)
	successReply := []byte{0x05, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00}
	
	_, err := client.Write(successReply)
	if err != nil {
		client.Close()
		return
	}

	target, err := net.Dial("tcp", targetAddr)
	if err != nil {
		client.Close()
		return
	}

	RelayTCP(client, target, isVVIP)
}

// RelayTCP menerapkan Aggressive Tear-down & Linux splice(2) Zero-Copy
func RelayTCP(client, target net.Conn, isVVIP bool) {
	// Wajib SetNoDelay(true) untuk melumpuhkan Nagle's Algorithm (Latency Tuning)
	if tcpClient, ok := client.(*net.TCPConn); ok {
		tcpClient.SetNoDelay(true)
	}
	if tcpTarget, ok := target.(*net.TCPConn); ok {
		tcpTarget.SetNoDelay(true)
	}

	var wg sync.WaitGroup
	wg.Add(2)

	pool := &relayPool32
	if isVVIP {
		pool = &vvipPool4M
		// Pencegahan pemotongan paket enkripsi (Spesifik VVIP)
		if tcpTarget, ok := target.(*net.TCPConn); ok {
			tcpTarget.SetReadBuffer(4 * 1024 * 1024)
		}
		log.Info().Msg("⚡ Arsitektur VVIP/MTProto (4MB) Diaktifkan")
	}

	// JALUR 1: Client -> Target
	go func() {
		defer wg.Done()
		// 🔴 Aggressive Tear-down: Larang channel <-done, tutup paksa kedua sisi!
		defer client.Close() 
		defer target.Close()

		bufPtr := pool.Get().(*[]byte)
		defer pool.Put(bufPtr)

		io.CopyBuffer(target, client, *bufPtr)
	}()

	// JALUR 2: Target -> Client
	go func() {
		defer wg.Done()
		// 🔴 Aggressive Tear-down
		defer target.Close()
		defer client.Close()

		bufPtr := pool.Get().(*[]byte)
		defer pool.Put(bufPtr)

		io.CopyBuffer(client, target, *bufPtr)
	}()

	wg.Wait()
}

// routeToSOCKS adalah jembatan sementara yang mengalihkan IP Lapis 3 ke Mesin Lapis 5
func routeToSOCKS(packet []byte) {
	// (Di sini Netstack gVisor akan ditanam nantinya untuk mengonversi IP menjadi net.Conn)
}
