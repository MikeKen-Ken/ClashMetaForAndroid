package tunnel

import (
	"encoding/json"
	"os"
	"sync"

	C "github.com/metacubex/mihomo/constant"
)

type proxyConnectivityEntry struct {
	Success int `json:"success"`
	Failure int `json:"failure"`
}

var (
	statsMu     sync.Mutex
	statsCache  map[string]proxyConnectivityEntry
	statsLoaded bool
)

func statsFilePath() string {
	return C.Path.Resolve("proxy-connectivity-stats.json")
}

func ensureStatsLoaded() {
	if statsLoaded {
		return
	}
	statsCache = make(map[string]proxyConnectivityEntry)
	raw, err := os.ReadFile(statsFilePath())
	if err == nil && len(raw) > 0 {
		_ = json.Unmarshal(raw, &statsCache)
	}
	if statsCache == nil {
		statsCache = make(map[string]proxyConnectivityEntry)
	}
	statsLoaded = true
}

func persistConnectivityStats() {
	if statsCache == nil {
		return
	}
	data, err := json.Marshal(statsCache)
	if err != nil {
		return
	}
	_ = os.WriteFile(statsFilePath(), data, 0o644)
}

// RecordDelayTestResult 根据测速 delay 累加成功/失败次数（0 < delay <= timeout 为成功）。
func RecordDelayTestResult(proxyName string, delay int, timeoutMs int) {
	if proxyName == "" || proxyName == "DIRECT" || proxyName == "REJECT" {
		return
	}
	if delay == -2 || delay == -1 {
		return
	}

	effectiveTimeout := timeoutMs
	if effectiveTimeout <= 0 {
		effectiveTimeout = 5000
	}
	isSuccess := delay > 0 && delay <= effectiveTimeout

	statsMu.Lock()
	defer statsMu.Unlock()
	ensureStatsLoaded()

	prev := statsCache[proxyName]
	if isSuccess {
		prev.Success++
	} else {
		prev.Failure++
	}
	statsCache[proxyName] = prev
	persistConnectivityStats()
}

func getConnectivitySuccessCount(proxyName string) int {
	statsMu.Lock()
	defer statsMu.Unlock()
	ensureStatsLoaded()
	return statsCache[proxyName].Success
}
