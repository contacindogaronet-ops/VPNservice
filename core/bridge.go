package core

import (
	"bufio"
	"fmt"
	"net"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

type RuleAction string

const (
	ActionDirect RuleAction = "DIRECT"
	ActionProxy  RuleAction = "PROXY"
	ActionBlock  RuleAction = "BLOCK"
)

type RuleEngine struct {
	mu          sync.RWMutex
	domainRules map[string]RuleAction
	ipRules     map[string]RuleAction
	ruleCount   int32
}

var (
	globalRuleEngine = &RuleEngine{
		domainRules: make(map[string]RuleAction),
		ipRules:     make(map[string]RuleAction),
	}

	logMutex    sync.Mutex
	logQueue    []string
	maxLogCap   = 250
	lastLogTime time.Time
	lastLogMsg  string
)

func AddLog(level string, message string) {
	logMutex.Lock()
	defer logMutex.Unlock()

	now := time.Now()
	if message == lastLogMsg && now.Sub(lastLogTime) < 1*time.Second {
		return
	}
	lastLogMsg = message
	lastLogTime = now

	timestamp := now.Format("15:04:05")
	formatted := fmt.Sprintf("[%s] [%s] %s", timestamp, strings.ToUpper(level), message)

	if len(logQueue) >= maxLogCap {
		logQueue = logQueue[1:]
	}
	logQueue = append(logQueue, formatted)
}

func PullLogs() string {
	logMutex.Lock()
	defer logMutex.Unlock()

	if len(logQueue) == 0 {
		return ""
	}

	var sb strings.Builder
	for i, line := range logQueue {
		sb.WriteString(line)
		if i < len(logQueue)-1 {
			sb.WriteString("\n")
		}
	}
	logQueue = logQueue[:0]
	return sb.String()
}

func LoadRules(ruleContent string) int {
	globalRuleEngine.mu.Lock()
	defer globalRuleEngine.mu.Unlock()

	globalRuleEngine.domainRules = make(map[string]RuleAction)
	globalRuleEngine.ipRules = make(map[string]RuleAction)

	scanner := bufio.NewScanner(strings.NewReader(ruleContent))
	count := 0

	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") || strings.HasPrefix(line, "//") {
			continue
		}

		parts := strings.Split(line, ",")
		if len(parts) < 3 {
			continue
		}

		ruleType := strings.ToUpper(strings.TrimSpace(parts[0]))
		payload := strings.ToLower(strings.TrimSpace(parts[1]))
		actionStr := strings.ToUpper(strings.TrimSpace(parts[2]))

		var action RuleAction
		switch actionStr {
		case "DIRECT":
			action = ActionDirect
		case "BLOCK":
			action = ActionBlock
		default:
			action = ActionProxy
		}

		switch ruleType {
		case "DOMAIN", "DOMAIN-SUFFIX":
			globalRuleEngine.domainRules[payload] = action
			count++
		case "IP", "IP-CIDR":
			globalRuleEngine.ipRules[payload] = action
			count++
		}
	}

	atomic.StoreInt32(&globalRuleEngine.ruleCount, int32(count))
	AddLog("INFO", fmt.Sprintf("Compiled %d routing rules", count))
	return count
}

func GetLoadedRulesCount() int {
	return int(atomic.LoadInt32(&globalRuleEngine.ruleCount))
}

// TestLocalPing mengukur latensi mesin lokal (127.0.0.3:2007) dalam milidetik.
func TestLocalPing(addr string) int {
	if addr == "" {
		addr = "127.0.0.3:2007"
	}
	start := time.Now()
	conn, err := net.DialTimeout("tcp", addr, 1000*time.Millisecond)
	if err != nil {
		return -1
	}
	_ = conn.Close()
	return int(time.Since(start).Milliseconds())
}

// TestGlobalPing mengukur latensi koneksi internet global ke 1.1.1.1:80 dalam milidetik.
func TestGlobalPing(target string) int {
	if target == "" {
		target = "1.1.1.1:80"
	}
	dialer := ProtectedDialer(2)
	start := time.Now()
	conn, err := dialer.Dial("tcp", target)
	if err != nil {
		return -1
	}
	_ = conn.Close()
	return int(time.Since(start).Milliseconds())
}
