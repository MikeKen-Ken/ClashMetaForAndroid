package connectivity

import "time"

// RecordDelayTestResult counts every completed test symmetrically. Suppressing
// only failures biases the success probability during repeated or shared-group
// checks. Canceled/skipped tests are not observations. The v2 shared counters
// stay additive, so every device's samples still contribute to the same score.
func RecordDelayTestResult(proxyName string, delay int, timeoutMs int) {
	if proxyName == "" || proxyName == "DIRECT" || proxyName == "REJECT" || delay == -2 || delay == -1 {
		return
	}
	if timeoutMs <= 0 {
		timeoutMs = defaultPenaltyDelayMs
	}
	now := time.Now()
	statsMu.Lock()
	defer statsMu.Unlock()
	ensureStatsLoaded()
	entry := statsCache[proxyName]
	if entry.Days == nil {
		entry.Days = make(map[string]dayCounts)
	}
	day := todayKey(now)
	counts := entry.Days[day]
	if delay > 0 && delay < timeoutMs {
		counts.Success = safeAddCount(counts.Success, 1)
		counts.DelaySum = safeAddCount(counts.DelaySum, int64(delay))
		entry.LastSuccessAt = now.Unix()
	} else {
		counts.Failure = safeAddCount(counts.Failure, 1)
		// A configurable test deadline must not change the ranking penalty for
		// an otherwise identical failed observation.
		counts.DelaySum = safeAddCount(counts.DelaySum, defaultPenaltyDelayMs)
	}
	entry.Days[day] = counts
	statsCache[proxyName] = entry
	pruneExpiredEntries(now)
	_ = persistConnectivityStats()
}
