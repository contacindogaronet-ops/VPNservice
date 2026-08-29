package core

import (
	"bufio"
	"fmt"
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
	maxLogCap   = 200 // Dibatasi ketat agar RAM tidak membengkak
	lastLogTime time.Time
	lastLogMsg  string
)

// AddLog menyaring log duplikat dan membatasi ukuran memori log.
func AddLog(level string, message string) {
	logMutex.Lock()
	defer logMutex.Unlock()

	now := time.Now()
	// Anti-Flood: Jangan catat pesan error yang sama persis dalam rentang 1 detik
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
	AddLog("INFO", fmt.Sprintf("Loaded %d routing rules", count))
	return count
}

func MatchDomain(domain string) RuleAction {
	globalRuleEngine.mu.RLock()
	defer globalRuleEngine.mu.RUnlock()

	domain = strings.ToLower(strings.TrimSpace(domain))
	if action, exists := globalRuleEngine.domainRules[domain]; exists {
		return action
	}

	for ruleDomain, action := range globalRuleEngine.domainRules {
		if strings.HasSuffix(domain, "."+ruleDomain) || domain == ruleDomain {
			return action
		}
	}

	return ActionProxy
}

func GetLoadedRulesCount() int {
	return int(atomic.LoadInt32(&globalRuleEngine.ruleCount))
}
