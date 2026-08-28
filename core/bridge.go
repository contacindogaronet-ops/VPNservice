package core

import (
	"sync"
)

var (
	logMutex  sync.Mutex
	logBuffer string
)

func AddLog(msg string) {
	logMutex.Lock()
	defer logMutex.Unlock()
	logBuffer += msg + "\n"
}

func PullLogs() string {
	logMutex.Lock()
	defer logMutex.Unlock()
	res := logBuffer
	logBuffer = ""
	return res
}

func LoadRules(rulesContent string) {
	AddLog("⚙️ [SYSTEM] Berhasil memuat matriks rules baru.")
}
