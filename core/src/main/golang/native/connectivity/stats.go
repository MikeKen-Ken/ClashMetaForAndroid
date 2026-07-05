package connectivity

import (
	"encoding/json"
	"math"
	"os"
	"sync"
	"time"

	C "github.com/metacubex/mihomo/constant"
)

const (
	retentionDays           = 30
	decayHalfLifeDays       = 3.0
	priorVirtualSamples     = 20.0
	fallbackDelayMs         = 400.0
	scoreReferenceDelayMs   = 400.0
	defaultPenaltyDelayMs   = 5000
)

type dayCounts struct {
	Success  int `json:"s"`
	Failure  int `json:"f"`
	DelaySum int `json:"ds,omitempty"`
}

type proxyConnectivityEntry struct {
	Days map[string]dayCounts `json:"days"`
}

type statsFileV2 struct {
	V    int                               `json:"v"`
	Data map[string]proxyConnectivityEntry `json:"data"`
}

type legacyEntry struct {
	Success int `json:"success"`
	Failure int `json:"failure"`
}

// WeightedStats 指数衰减加权后的成功/失败/有效延迟总和（可为小数）。
type WeightedStats struct {
	Success  float64
	Failure  float64
	DelaySum float64
}

// ScoreContext 批量排序时一次性构建，避免重复扫描统计。
type ScoreContext struct {
	byProxy      map[string]WeightedStats
	priorDelayMs float64
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

func dayAgeInDays(dayKey string, today time.Time) int {
	parsed, err := time.ParseInLocation("2006-01-02", dayKey, today.Location())
	if err != nil {
		return math.MaxInt32
	}
	todayStart := time.Date(today.Year(), today.Month(), today.Day(), 0, 0, 0, 0, today.Location())
	dayStart := time.Date(parsed.Year(), parsed.Month(), parsed.Day(), 0, 0, 0, 0, parsed.Location())
	return int(todayStart.Sub(dayStart).Hours() / 24)
}

func DecayWeight(ageDays int) float64 {
	if ageDays < 0 || ageDays >= retentionDays {
		return 0
	}
	if decayHalfLifeDays <= 0 {
		if ageDays == 0 {
			return 1
		}
		return 0
	}
	return math.Pow(0.5, float64(ageDays)/decayHalfLifeDays)
}

func sumWeightedDays(days map[string]dayCounts, today time.Time) WeightedStats {
	var stats WeightedStats
	for day, counts := range days {
		weight := DecayWeight(dayAgeInDays(day, today))
		if weight <= 0 {
			continue
		}
		stats.Success += float64(counts.Success) * weight
		stats.Failure += float64(counts.Failure) * weight
		stats.DelaySum += float64(counts.DelaySum) * weight
	}
	return stats
}

func weightedTrialCount(stats WeightedStats) float64 {
	return stats.Success + stats.Failure
}

func collectWeightedStatsFromCache(
	cache map[string]proxyConnectivityEntry,
	today time.Time,
) (WeightedStats, map[string]WeightedStats) {
	global := WeightedStats{}
	byProxy := make(map[string]WeightedStats)
	for name, entry := range cache {
		if len(entry.Days) == 0 {
			continue
		}
		weighted := sumWeightedDays(entry.Days, today)
		if weighted.Success <= 0 && weighted.Failure <= 0 {
			continue
		}
		byProxy[name] = weighted
		global.Success += weighted.Success
		global.Failure += weighted.Failure
		global.DelaySum += weighted.DelaySum
	}
	return global, byProxy
}

func computePriorEffectiveDelayMs(global WeightedStats) float64 {
	trials := weightedTrialCount(global)
	if trials <= 0 {
		return fallbackDelayMs
	}
	avg := global.DelaySum / trials
	if math.IsNaN(avg) || math.IsInf(avg, 0) || avg < 0 {
		return fallbackDelayMs
	}
	return avg
}

func smoothedEffectiveAvgDelay(stats WeightedStats, priorDelayMs float64) float64 {
	trials := weightedTrialCount(stats)
	prior := priorDelayMs
	if math.IsNaN(prior) || math.IsInf(prior, 0) || prior <= 0 {
		prior = fallbackDelayMs
	}
	return (stats.DelaySum + priorVirtualSamples*prior) / (trials + priorVirtualSamples)
}

func connectivityScoreFromAvgDelay(avgDelayMs float64) float64 {
	if math.IsNaN(avgDelayMs) || math.IsInf(avgDelayMs, 0) || avgDelayMs < 0 {
		return 1.0 / (1.0 + fallbackDelayMs/scoreReferenceDelayMs)
	}
	return 1.0 / (1.0 + avgDelayMs/scoreReferenceDelayMs)
}

func penalizedDelayScore(stats WeightedStats, priorDelayMs float64) float64 {
	avg := smoothedEffectiveAvgDelay(stats, priorDelayMs)
	return connectivityScoreFromAvgDelay(avg)
}

func BuildScoreContext() ScoreContext {
	statsMu.Lock()
	defer statsMu.Unlock()
	ensureStatsLoaded()
	today := time.Now()
	global, byProxy := collectWeightedStatsFromCache(statsCache, today)
	return ScoreContext{
		byProxy:      byProxy,
		priorDelayMs: computePriorEffectiveDelayMs(global),
	}
}

func (ctx ScoreContext) ScoreFor(proxyName string) float64 {
	stats := ctx.byProxy[proxyName]
	return penalizedDelayScore(stats, ctx.priorDelayMs)
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

// RecordDelayTestResult 成功记真实 delay，失败记 timeout 惩罚延迟，最多保留 30 天。
func RecordDelayTestResult(proxyName string, delay int, timeoutMs int) {
	if proxyName == "" || proxyName == "DIRECT" || proxyName == "REJECT" {
		return
	}
	if delay == -2 || delay == -1 {
		return
	}

	effectiveTimeout := timeoutMs
	if effectiveTimeout <= 0 {
		effectiveTimeout = defaultPenaltyDelayMs
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
		counts.DelaySum += delay
	} else {
		counts.Failure++
		counts.DelaySum += effectiveTimeout
	}
	entry.Days[day] = counts
	pruneDays(entry.Days, now)
	statsCache[proxyName] = entry
	persistConnectivityStats()
}

func ClearAll() {
	statsMu.Lock()
	defer statsMu.Unlock()
	statsCache = make(map[string]proxyConnectivityEntry)
	statsLoaded = true
	_ = os.Remove(statsFilePath())
}
