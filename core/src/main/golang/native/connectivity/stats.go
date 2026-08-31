package connectivity

import (
	"encoding/json"
	"errors"
	"math"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"time"

	C "github.com/metacubex/mihomo/constant"
)

const (
	retentionDays               = 30
	decayHalfLifeDays           = 3.0
	priorVirtualSamples         = 20.0
	fallbackDelayMs             = 400.0
	scoreReferenceDelayMs       = 400.0
	defaultPenaltyDelayMs       = 5000
	maxSafeCount          int64 = 9_007_199_254_740_991
	// 同一节点一分钟内最多记 1 次失败，避免重连/健康检查风暴把分数打崩。
	failureRecordMinInterval = time.Minute
)

type dayCounts struct {
	Success  int64 `json:"s"`
	Failure  int64 `json:"f"`
	DelaySum int64 `json:"ds,omitempty"`
}

type proxyConnectivityEntry struct {
	Days map[string]dayCounts `json:"days"`
}

type statsFileV2 struct {
	V    int                               `json:"v"`
	Data map[string]proxyConnectivityEntry `json:"data"`
	Sync *statsSyncState                   `json:"_sync,omitempty"`
}

type statsSyncState struct {
	LastOthers      map[string]proxyConnectivityEntry `json:"lastOthers"`
	ResetWatermarks map[string]resetGeneration        `json:"resetWatermarks,omitempty"`
}

type statsSyncMergeResult struct {
	OK     bool                              `json:"ok"`
	Error  string                            `json:"error,omitempty"`
	Own    map[string]proxyConnectivityEntry `json:"own,omitempty"`
	Merged map[string]proxyConnectivityEntry `json:"merged,omitempty"`
	Resets map[string]resetGeneration        `json:"resets,omitempty"`
}

type legacyEntry struct {
	Success int64 `json:"success"`
	Failure int64 `json:"failure"`
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
	statsMu            sync.Mutex
	statsCache         map[string]proxyConnectivityEntry
	statsLastOthers    map[string]proxyConnectivityEntry
	statsHasBaseline   bool
	statsResets        map[string]resetGeneration
	statsLoaded        bool
	lastFailureAt      map[string]time.Time
	failureMinInterval = failureRecordMinInterval // 单测可改短
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

// pruneExpiredEntries 清理各节点过期天键；days 已空则删除 proxy 条目，并清掉孤儿 lastFailureAt。
// 调用方须已持有 statsMu。
func pruneExpiredEntries(now time.Time) (changed bool) {
	if statsCache == nil {
		return false
	}
	for name, entry := range statsCache {
		if entry.Days == nil {
			delete(statsCache, name)
			delete(lastFailureAt, name)
			changed = true
			continue
		}
		before := len(entry.Days)
		pruneDays(entry.Days, now)
		if len(entry.Days) == 0 {
			delete(statsCache, name)
			delete(lastFailureAt, name)
			changed = true
			continue
		}
		if len(entry.Days) != before {
			statsCache[name] = entry
			changed = true
		}
	}
	for name, at := range lastFailureAt {
		if _, ok := statsCache[name]; !ok {
			delete(lastFailureAt, name)
			changed = true
			continue
		}
		if now.Sub(at) >= failureMinInterval {
			delete(lastFailureAt, name)
			changed = true
		}
	}
	return changed
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
	statsLastOthers = nil
	statsHasBaseline = false
	statsResets = make(map[string]resetGeneration)

	raw, err := os.ReadFile(statsFilePath())
	if err == nil && len(raw) > 0 {
		var file statsFileV2
		if json.Unmarshal(raw, &file) == nil && file.V == 2 && file.Data != nil {
			statsCache = file.Data
			if file.Sync != nil {
				statsLastOthers = pruneStatsData(file.Sync.LastOthers, time.Now())
				if sanitized, sanitizeErr := sanitizeResetWatermarks(
					file.Sync.ResetWatermarks,
				); sanitizeErr == nil {
					statsResets = sanitized
				} else {
					// Preserve invalid/oversized state so later merge/reset operations fail
					// closed instead of silently forgetting every reset watermark.
					statsResets = file.Sync.ResetWatermarks
				}
				statsHasBaseline = true
			}
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
	if pruneExpiredEntries(time.Now()) {
		persistConnectivityStats()
	}
	statsLoaded = true
}

func persistConnectivityStatsData(
	data map[string]proxyConnectivityEntry,
	lastOthers map[string]proxyConnectivityEntry,
	resetWatermarks map[string]resetGeneration,
	hasBaseline bool,
) error {
	payload := statsFileV2{V: 2, Data: data}
	if hasBaseline || len(resetWatermarks) > 0 {
		payload.Sync = &statsSyncState{
			LastOthers:      lastOthers,
			ResetWatermarks: resetWatermarks,
		}
	}
	encoded, err := json.Marshal(payload)
	if err != nil {
		return err
	}

	path := statsFilePath()
	temporary, err := os.CreateTemp(filepath.Dir(path), ".proxy-connectivity-stats-*.tmp")
	if err != nil {
		return err
	}
	temporaryPath := temporary.Name()
	defer os.Remove(temporaryPath)

	if err := temporary.Chmod(0o644); err != nil {
		_ = temporary.Close()
		return err
	}
	if _, err := temporary.Write(encoded); err != nil {
		_ = temporary.Close()
		return err
	}
	if err := temporary.Sync(); err != nil {
		_ = temporary.Close()
		return err
	}
	if err := temporary.Close(); err != nil {
		return err
	}
	return os.Rename(temporaryPath, path)
}

func persistConnectivityStats() error {
	if statsCache == nil {
		return nil
	}
	return persistConnectivityStatsData(statsCache, statsLastOthers, statsResets, statsHasBaseline)
}

// ExportRaw returns the authoritative version-2 per-day counters for WebDAV sync.
func ExportRaw() string {
	statsMu.Lock()
	defer statsMu.Unlock()
	ensureStatsLoaded()
	if pruneExpiredEntries(time.Now()) {
		_ = persistConnectivityStats()
	}
	payload := statsFileV2{V: 2, Data: statsCache}
	data, err := json.Marshal(payload)
	if err != nil {
		return `{"v":2,"data":{}}`
	}
	return string(data)
}

// ReplaceRaw atomically replaces the aggregate counters after a validated merge.
func ReplaceRaw(raw string) bool {
	var payload statsFileV2
	if json.Unmarshal([]byte(raw), &payload) != nil || payload.V != 2 || payload.Data == nil {
		return false
	}
	for _, entry := range payload.Data {
		for _, counts := range entry.Days {
			if counts.Success < 0 || counts.Failure < 0 || counts.DelaySum < 0 {
				return false
			}
		}
	}

	statsMu.Lock()
	defer statsMu.Unlock()
	ensureStatsLoaded()
	candidate := pruneStatsData(payload.Data, time.Now())
	if persistConnectivityStatsData(candidate, statsLastOthers, statsResets, statsHasBaseline) != nil {
		return false
	}
	statsCache = candidate
	lastFailureAt = make(map[string]time.Time)
	statsLoaded = true
	return true
}

func pruneStatsData(
	data map[string]proxyConnectivityEntry,
	now time.Time,
) map[string]proxyConnectivityEntry {
	pruned := make(map[string]proxyConnectivityEntry)
	for name, entry := range data {
		days := make(map[string]dayCounts)
		for day, counts := range entry.Days {
			if day >= cutoffDayKey(now) && day <= todayKey(now) &&
				(counts.Success > 0 || counts.Failure > 0 || counts.DelaySum > 0) {
				days[day] = counts
			}
		}
		if len(days) > 0 {
			pruned[name] = proxyConnectivityEntry{Days: days}
		}
	}
	return pruned
}

func subtractStats(
	current map[string]proxyConnectivityEntry,
	imported map[string]proxyConnectivityEntry,
) map[string]proxyConnectivityEntry {
	result := make(map[string]proxyConnectivityEntry)
	for name, entry := range current {
		days := make(map[string]dayCounts)
		previousDays := imported[name].Days
		for day, counts := range entry.Days {
			previous := previousDays[day]
			own := dayCounts{
				Success:  nonNegativeSubtract(counts.Success, previous.Success),
				Failure:  nonNegativeSubtract(counts.Failure, previous.Failure),
				DelaySum: nonNegativeSubtract(counts.DelaySum, previous.DelaySum),
			}
			if own.Success > 0 || own.Failure > 0 || own.DelaySum > 0 {
				days[day] = own
			}
		}
		if len(days) > 0 {
			result[name] = proxyConnectivityEntry{Days: days}
		}
	}
	return result
}

func nonNegativeSubtract(current, previous int64) int64 {
	if current <= previous {
		return 0
	}
	return current - previous
}

func safeAddCount(left, right int64) int64 {
	if left < 0 || right < 0 {
		return 0
	}
	if left > maxSafeCount-right {
		return maxSafeCount
	}
	return left + right
}

func sumStats(parts ...map[string]proxyConnectivityEntry) map[string]proxyConnectivityEntry {
	merged := make(map[string]proxyConnectivityEntry)
	for _, data := range parts {
		for name, entry := range data {
			target := merged[name]
			if target.Days == nil {
				target.Days = make(map[string]dayCounts)
			}
			for day, counts := range entry.Days {
				current := target.Days[day]
				target.Days[day] = dayCounts{
					Success:  safeAddCount(current.Success, counts.Success),
					Failure:  safeAddCount(current.Failure, counts.Failure),
					DelaySum: safeAddCount(current.DelaySum, counts.DelaySum),
				}
			}
			merged[name] = target
		}
	}
	return pruneStatsData(merged, time.Now())
}

func decodeStatsData(raw string) (map[string]proxyConnectivityEntry, error) {
	var payload statsFileV2
	if err := json.Unmarshal([]byte(raw), &payload); err != nil {
		return nil, err
	}
	if payload.V != 2 || payload.Data == nil {
		return nil, errors.New("unsupported connectivity statistics version")
	}
	for _, entry := range payload.Data {
		for _, counts := range entry.Days {
			if counts.Success < 0 || counts.Failure < 0 || counts.DelaySum < 0 {
				return nil, errors.New("negative connectivity statistics")
			}
		}
	}
	return pruneStatsData(payload.Data, time.Now()), nil
}

func encodeMergeResult(result statsSyncMergeResult) string {
	encoded, err := json.Marshal(result)
	if err != nil {
		return `{"ok":false,"error":"failed to encode connectivity merge result"}`
	}
	return string(encoded)
}

// MergeRaw owns the final local merge and persists the aggregate and imported
// baseline together. WebDAV I/O remains outside statsMu.
func MergeRaw(previousOthersRaw, remoteOthersRaw, resetWatermarksRaw string) string {
	fallbackOthers, err := decodeStatsData(previousOthersRaw)
	if err != nil {
		return encodeMergeResult(statsSyncMergeResult{OK: false, Error: err.Error()})
	}
	remoteOthers, err := decodeStatsData(remoteOthersRaw)
	if err != nil {
		return encodeMergeResult(statsSyncMergeResult{OK: false, Error: err.Error()})
	}
	incomingResets, err := decodeResetWatermarks(resetWatermarksRaw)
	if err != nil {
		return encodeMergeResult(statsSyncMergeResult{OK: false, Error: err.Error()})
	}

	statsMu.Lock()
	defer statsMu.Unlock()
	ensureStatsLoaded()

	current := pruneStatsData(statsCache, time.Now())
	baseline := pruneStatsData(fallbackOthers, time.Now())
	if statsHasBaseline {
		baseline = pruneStatsData(statsLastOthers, time.Now())
	}
	activeResets, err := mergeResetWatermarks(statsResets, incomingResets)
	if err != nil {
		return encodeMergeResult(statsSyncMergeResult{OK: false, Error: err.Error()})
	}
	removeAdvancedResetData(current, baseline, statsResets, activeResets)
	own := pruneStatsData(subtractStats(current, baseline), time.Now())
	merged := sumStats(own, remoteOthers)
	if err := persistConnectivityStatsData(merged, remoteOthers, activeResets, true); err != nil {
		return encodeMergeResult(statsSyncMergeResult{OK: false, Error: err.Error()})
	}

	statsCache = merged
	statsLastOthers = remoteOthers
	statsResets = activeResets
	statsHasBaseline = true
	lastFailureAt = make(map[string]time.Time)
	statsLoaded = true
	return encodeMergeResult(statsSyncMergeResult{
		OK:     true,
		Own:    own,
		Merged: merged,
		Resets: activeResets,
	})
}

// RecordDelayTestResult 成功记真实 delay，失败记 timeout 惩罚延迟，最多保留 30 天。
// 同一节点在 failureMinInterval 内的重复失败只记一次。
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

	if !isSuccess {
		if lastFailureAt == nil {
			lastFailureAt = make(map[string]time.Time)
		}
		if last, ok := lastFailureAt[proxyName]; ok && now.Sub(last) < failureMinInterval {
			return
		}
	}

	entry := statsCache[proxyName]
	if entry.Days == nil {
		entry.Days = make(map[string]dayCounts)
	}
	counts := entry.Days[day]
	if isSuccess {
		counts.Success++
		counts.DelaySum += int64(delay)
	} else {
		counts.Failure++
		counts.DelaySum += int64(effectiveTimeout)
		lastFailureAt[proxyName] = now
	}
	entry.Days[day] = counts
	pruneDays(entry.Days, now)
	if len(entry.Days) == 0 {
		delete(statsCache, proxyName)
		delete(lastFailureAt, proxyName)
	} else {
		statsCache[proxyName] = entry
	}
	// 顺带清掉其他节点已过期的空条目，避免换订阅后历史节点名只增不减
	_ = pruneExpiredEntries(now)
	_ = persistConnectivityStats()
}

func ClearAll() {
	statsMu.Lock()
	defer statsMu.Unlock()
	statsCache = make(map[string]proxyConnectivityEntry)
	statsLastOthers = nil
	statsHasBaseline = false
	statsResets = make(map[string]resetGeneration)
	lastFailureAt = make(map[string]time.Time)
	statsLoaded = true
	_ = os.Remove(statsFilePath())
}

// ClearAllWithResets clears the aggregate while atomically preserving the
// reset generations that make the deletion win over older remote snapshots.
func ClearAllWithResets(resetWatermarksRaw string) bool {
	incomingResets, err := decodeResetWatermarks(resetWatermarksRaw)
	if err != nil {
		return false
	}
	statsMu.Lock()
	defer statsMu.Unlock()
	ensureStatsLoaded()
	activeResets, err := mergeResetWatermarks(statsResets, incomingResets)
	if err != nil {
		return false
	}
	empty := make(map[string]proxyConnectivityEntry)
	if err := persistConnectivityStatsData(empty, empty, activeResets, true); err != nil {
		return false
	}
	statsCache = empty
	statsLastOthers = empty
	statsHasBaseline = true
	statsResets = activeResets
	lastFailureAt = make(map[string]time.Time)
	statsLoaded = true
	return true
}

// ClearProxy 清空单个节点的测速联通统计。
func ClearProxy(proxyName string) {
	if proxyName == "" {
		return
	}
	statsMu.Lock()
	defer statsMu.Unlock()
	ensureStatsLoaded()
	if _, ok := statsCache[proxyName]; !ok {
		return
	}
	delete(statsCache, proxyName)
	if statsHasBaseline {
		delete(statsLastOthers, proxyName)
	}
	delete(lastFailureAt, proxyName)
	_ = persistConnectivityStats()
}

// ClearProxyWithResets clears one node and stores its reset generation in the
// same file replacement, so a crash cannot expose the deletion without its
// reset watermark (or vice versa).
func ClearProxyWithResets(proxyName, resetWatermarksRaw string) bool {
	if proxyName == "" {
		return false
	}
	incomingResets, err := decodeResetWatermarks(resetWatermarksRaw)
	if err != nil {
		return false
	}
	statsMu.Lock()
	defer statsMu.Unlock()
	ensureStatsLoaded()
	current := pruneStatsData(statsCache, time.Now())
	baseline := pruneStatsData(statsLastOthers, time.Now())
	delete(current, proxyName)
	delete(baseline, proxyName)
	activeResets, err := mergeResetWatermarks(statsResets, incomingResets)
	if err != nil {
		return false
	}
	if err := persistConnectivityStatsData(current, baseline, activeResets, statsHasBaseline); err != nil {
		return false
	}
	statsCache = current
	statsLastOthers = baseline
	statsResets = activeResets
	delete(lastFailureAt, proxyName)
	statsLoaded = true
	return true
}

// ScoreRow 面板列表行。
type ScoreRow struct {
	Name                string  `json:"name"`
	Score               float64 `json:"score"`
	WeightedSuccess     float64 `json:"weightedSuccess"`
	WeightedFailure     float64 `json:"weightedFailure"`
	EffectiveAvgDelayMs float64 `json:"effectiveAvgDelayMs"`
	HasStats            bool    `json:"hasStats"`
}

// QueryScoreRows 按联通分降序列出节点（同分保序）。
func QueryScoreRows(names []string) []ScoreRow {
	if len(names) == 0 {
		return []ScoreRow{}
	}
	ctx := BuildScoreContext()
	type keyed struct {
		index int
		row   ScoreRow
	}
	keys := make([]keyed, len(names))
	for i, name := range names {
		stats := ctx.byProxy[name]
		hasStats := stats.Success > 0 || stats.Failure > 0
		avg := smoothedEffectiveAvgDelay(stats, ctx.priorDelayMs)
		keys[i] = keyed{
			index: i,
			row: ScoreRow{
				Name:                name,
				Score:               penalizedDelayScore(stats, ctx.priorDelayMs),
				WeightedSuccess:     stats.Success,
				WeightedFailure:     stats.Failure,
				EffectiveAvgDelayMs: avg,
				HasStats:            hasStats,
			},
		}
	}
	sort.SliceStable(keys, func(i, j int) bool {
		a, b := keys[i], keys[j]
		if a.row.Score != b.row.Score {
			return a.row.Score > b.row.Score
		}
		return a.index < b.index
	})
	out := make([]ScoreRow, len(keys))
	for i, k := range keys {
		out[i] = k.row
	}
	return out
}
