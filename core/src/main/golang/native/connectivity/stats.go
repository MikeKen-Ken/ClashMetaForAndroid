package connectivity

import (
	"encoding/json"
	"os"
	"sync"
	"time"

	C "github.com/metacubex/mihomo/constant"
)

const retentionDays = 3

type dayCounts struct {
	Success int `json:"s"`
	Failure int `json:"f"`
}

type proxyConnectivityEntry struct {
	Days map[string]dayCounts `json:"days"`
}

type statsFileV2 struct {
	V    int                               `json:"v"`
	Data map[string]proxyConnectivityEntry `json:"data"`
}

// legacy flat entry (v1)
type legacyEntry struct {
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

func todayKey(now time.Time) string {
	return now.Format("2006-01-02")
}

func cutoffDayKey(now time.Time) string {
	return now.AddDate(0, 0, -(retentionDays - 1)).Format("2006-01-02")
}

func pruneDays(days map[string]dayCounts, now time.Time) {
	if len(days) == 0 {
		return
	}
	cutoff := cutoffDayKey(now)
	for key := range days {
		if key < cutoff {
			delete(days, key)
		}
	}
}

func sumSuccess(days map[string]dayCounts) int {
	total := 0
	for _, counts := range days {
		total += counts.Success
	}
	return total
}

func ensureStatsLoaded() {
	if statsLoaded {
		return
	}
	statsCache = make(map[string]proxyConnectivityEntry)

	raw, err := os.ReadFile(statsFilePath())
	if err == nil && len(raw) > 0 {
		var file statsFileV2
		if json.Unmarshal(raw, &file) == nil && file.V == 2 && file.Data != nil {
			statsCache = file.Data
		} else {
			var legacy map[string]legacyEntry
			if json.Unmarshal(raw, &legacy) == nil {
				today := todayKey(time.Now())
				for name, entry := range legacy {
					if entry.Success == 0 && entry.Failure == 0 {
						continue
					}
					statsCache[name] = proxyConnectivityEntry{
						Days: map[string]dayCounts{
							today: {Success: entry.Success, Failure: entry.Failure},
						},
					}
				}
			}
		}
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
	payload := statsFileV2{V: 2, Data: statsCache}
	data, err := json.Marshal(payload)
	if err != nil {
		return
	}
	_ = os.WriteFile(statsFilePath(), data, 0o644)
}

// RecordDelayTestResult 根据测速 delay 累加成功/失败（0 < delay <= timeout 为成功），仅保留最近 3 天。
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

	now := time.Now()
	day := todayKey(now)

	statsMu.Lock()
	defer statsMu.Unlock()
	ensureStatsLoaded()

	entry := statsCache[proxyName]
	if entry.Days == nil {
		entry.Days = make(map[string]dayCounts)
	}
	counts := entry.Days[day]
	if isSuccess {
		counts.Success++
	} else {
		counts.Failure++
	}
	entry.Days[day] = counts
	pruneDays(entry.Days, now)
	statsCache[proxyName] = entry
	persistConnectivityStats()
}

// GetSuccessCount 返回节点在最近 3 天内的测速成功次数总和。
func GetSuccessCount(proxyName string) int {
	if proxyName == "" {
		return 0
	}

	now := time.Now()
	statsMu.Lock()
	defer statsMu.Unlock()
	ensureStatsLoaded()

	entry, ok := statsCache[proxyName]
	if !ok || entry.Days == nil {
		return 0
	}
	pruneDays(entry.Days, now)
	if len(entry.Days) == 0 {
		delete(statsCache, proxyName)
		return 0
	}
	statsCache[proxyName] = entry
	return sumSuccess(entry.Days)
}

// ClearAll 清空全部测速联通统计（本地文件一并删除）。
func ClearAll() {
	statsMu.Lock()
	defer statsMu.Unlock()
	statsCache = make(map[string]proxyConnectivityEntry)
	statsLoaded = true
	_ = os.Remove(statsFilePath())
}
