package connectivity

import (
	"testing"
	"time"
)

func TestDecayWeightHalvesEveryHalfLife(t *testing.T) {
	if DecayWeight(0) != 1 {
		t.Fatalf("today weight = %v, want 1", DecayWeight(0))
	}
	if diff := DecayWeight(3) - 0.5; diff < -1e-9 || diff > 1e-9 {
		t.Fatalf("3-day weight = %v, want 0.5", DecayWeight(3))
	}
	if DecayWeight(30) != 0 {
		t.Fatalf("30-day weight = %v, want 0", DecayWeight(30))
	}
}

func TestFailurePenaltyRaisesAvgDelay(t *testing.T) {
	prior := 400.0
	mostlyFast := penalizedDelayScore(WeightedStats{
		Success:  10,
		Failure:  0,
		DelaySum: 10 * 200,
	}, prior)
	withFailure := penalizedDelayScore(WeightedStats{
		Success:  10,
		Failure:  1,
		DelaySum: 10*200 + 5000,
	}, prior)
	if mostlyFast <= withFailure {
		t.Fatalf("mostlyFast=%v should beat withFailure=%v", mostlyFast, withFailure)
	}
}

func TestSmoothedAvgUsesPriorForSmallSample(t *testing.T) {
	avg := smoothedEffectiveAvgDelay(WeightedStats{
		Success:  1,
		Failure:  0,
		DelaySum: 100,
	}, 400)
	if avg <= 100 || avg >= 400 {
		t.Fatalf("smoothed avg=%v, want between 100 and 400", avg)
	}
}

func TestSortNamesByConnectivityOrder(t *testing.T) {
	ctx := ScoreContext{
		byProxy: map[string]WeightedStats{
			"node-low": {
				Success:  2,
				Failure:  8,
				DelaySum: 2*200 + 8*5000,
			},
			"node-high": {
				Success:  45,
				Failure:  2,
				DelaySum: 45*200 + 2*5000,
			},
		},
		priorDelayMs: 400,
	}
	if ctx.ScoreFor("node-high") <= ctx.ScoreFor("node-low") {
		t.Fatal("node-high should beat node-low")
	}
	if ctx.ScoreFor("node-untested") <= ctx.ScoreFor("node-low") {
		t.Fatal("untested should beat clearly bad node")
	}
}

func TestRecordFailureAddsPenaltyDelay(t *testing.T) {
	statsMu.Lock()
	statsCache = make(map[string]proxyConnectivityEntry)
	lastFailureAt = make(map[string]time.Time)
	statsLoaded = true
	statsMu.Unlock()

	RecordDelayTestResult("test-node", 0, 5000)

	statsMu.Lock()
	entry := statsCache["test-node"]
	statsMu.Unlock()
	if entry.Days == nil {
		t.Fatal("expected day entry")
	}
	var found dayCounts
	for _, counts := range entry.Days {
		found = counts
		break
	}
	if found.Failure != 1 || found.DelaySum != 5000 {
		t.Fatalf("counts=%+v, want f=1 ds=5000", found)
	}

	ClearAll()
}

func TestFailureDedupWithinOneMinute(t *testing.T) {
	statsMu.Lock()
	statsCache = make(map[string]proxyConnectivityEntry)
	lastFailureAt = make(map[string]time.Time)
	statsLoaded = true
	origInterval := failureMinInterval
	failureMinInterval = time.Minute
	statsMu.Unlock()
	defer func() {
		statsMu.Lock()
		failureMinInterval = origInterval
		statsMu.Unlock()
		ClearAll()
	}()

	// 一分钟内多次失败只记一次
	RecordDelayTestResult("dedup-node", 0, 5000)
	RecordDelayTestResult("dedup-node", 0, 5000)
	RecordDelayTestResult("dedup-node", 0, 5000)

	statsMu.Lock()
	entry := statsCache["dedup-node"]
	var found dayCounts
	for _, counts := range entry.Days {
		found = counts
		break
	}
	statsMu.Unlock()
	if found.Failure != 1 || found.DelaySum != 5000 {
		t.Fatalf("burst failures counts=%+v, want f=1 ds=5000", found)
	}

	// 成功不受失败去重影响，始终记账
	RecordDelayTestResult("dedup-node", 200, 5000)
	RecordDelayTestResult("dedup-node", 300, 5000)

	statsMu.Lock()
	entry = statsCache["dedup-node"]
	found = dayCounts{}
	for _, counts := range entry.Days {
		found = counts
		break
	}
	// 模拟窗口已过：上次失败时间推到一分钟前
	lastFailureAt["dedup-node"] = time.Now().Add(-time.Minute - time.Second)
	statsMu.Unlock()

	if found.Success != 2 || found.Failure != 1 || found.DelaySum != 5000+200+300 {
		t.Fatalf("after success counts=%+v, want s=2 f=1 ds=5500", found)
	}

	RecordDelayTestResult("dedup-node", 0, 5000)

	statsMu.Lock()
	entry = statsCache["dedup-node"]
	found = dayCounts{}
	for _, counts := range entry.Days {
		found = counts
		break
	}
	statsMu.Unlock()
	if found.Success != 2 || found.Failure != 2 || found.DelaySum != 5000+200+300+5000 {
		t.Fatalf("after window counts=%+v, want s=2 f=2 ds=10500", found)
	}
}

func TestOlderDayCountsLessThanToday(t *testing.T) {
	today := time.Date(2026, 7, 5, 12, 0, 0, 0, time.Local)
	days := map[string]dayCounts{
		"2026-07-05": {Success: 10, Failure: 0, DelaySum: 3000},
		"2026-07-02": {Success: 10, Failure: 0, DelaySum: 3000},
	}
	weighted := sumWeightedDays(days, today)
	if weighted.Success <= 10 || weighted.Success >= 20 {
		t.Fatalf("weighted success=%v, want between 10 and 20", weighted.Success)
	}
}

func syncTestData(success, failure int64) map[string]proxyConnectivityEntry {
	day := todayKey(time.Now())
	return map[string]proxyConnectivityEntry{
		"node": {
			Days: map[string]dayCounts{
				day: {
					Success:  success,
					Failure:  failure,
					DelaySum: success*200 + failure*5000,
				},
			},
		},
	}
}

func syncTestCounts(data map[string]proxyConnectivityEntry) dayCounts {
	return data["node"].Days[todayKey(time.Now())]
}

func TestSyncMergeUsesFreshLocalContribution(t *testing.T) {
	current := syncTestData(8, 3)
	remoteOthers := syncTestData(5, 1)
	own := subtractStats(current, remoteOthers)
	merged := sumStats(own, remoteOthers)

	if counts := syncTestCounts(own); counts.Success != 3 || counts.Failure != 2 {
		t.Fatalf("own=%+v, want s=3 f=2", counts)
	}
	if counts := syncTestCounts(merged); counts.Success != 8 || counts.Failure != 3 {
		t.Fatalf("merged=%+v, want s=8 f=3", counts)
	}
}

func TestSyncRetryUsesPersistedBaselineIdempotently(t *testing.T) {
	current := syncTestData(8, 3)
	persistedBaseline := syncTestData(5, 1)
	own := subtractStats(current, persistedBaseline)
	merged := sumStats(own, persistedBaseline)

	if counts := syncTestCounts(merged); counts.Success != 8 || counts.Failure != 3 {
		t.Fatalf("retry merged=%+v, want s=8 f=3", counts)
	}
}

func TestSyncMergePreservesCountersRecordedDuringWebDav(t *testing.T) {
	latestCurrent := syncTestData(9, 3)
	persistedBaseline := syncTestData(5, 1)
	own := subtractStats(latestCurrent, persistedBaseline)
	merged := sumStats(own, persistedBaseline)

	if counts := syncTestCounts(own); counts.Success != 4 || counts.Failure != 2 {
		t.Fatalf("own=%+v, want s=4 f=2", counts)
	}
	if counts := syncTestCounts(merged); counts.Success != 9 || counts.Failure != 3 {
		t.Fatalf("merged=%+v, want s=9 f=3", counts)
	}
}

func TestClearProxyRemovesOnlyTarget(t *testing.T) {
	statsMu.Lock()
	statsCache = map[string]proxyConnectivityEntry{
		"keep": {Days: map[string]dayCounts{"2026-07-22": {Success: 1, DelaySum: 100}}},
		"drop": {Days: map[string]dayCounts{"2026-07-22": {Success: 2, DelaySum: 200}}},
	}
	statsLoaded = true
	statsMu.Unlock()

	ClearProxy("drop")

	statsMu.Lock()
	_, dropOk := statsCache["drop"]
	_, keepOk := statsCache["keep"]
	statsMu.Unlock()
	if dropOk {
		t.Fatal("drop should be removed")
	}
	if !keepOk {
		t.Fatal("keep should remain")
	}
	ClearAll()
}

func TestPruneExpiredEntriesRemovesEmptyProxyKeys(t *testing.T) {
	now := time.Now()
	expiredDay := now.AddDate(0, 0, -(retentionDays + 1)).Format("2006-01-02")
	freshDay := todayKey(now)

	statsMu.Lock()
	statsCache = map[string]proxyConnectivityEntry{
		"stale": {Days: map[string]dayCounts{expiredDay: {Success: 1, DelaySum: 100}}},
		"keep":  {Days: map[string]dayCounts{freshDay: {Success: 2, DelaySum: 200}}},
		"empty": {Days: map[string]dayCounts{}},
	}
	lastFailureAt = map[string]time.Time{
		"stale": now.Add(-2 * time.Minute),
		"keep":  now.Add(-2 * time.Minute),
		"orphan": now.Add(-time.Hour),
	}
	statsLoaded = true
	changed := pruneExpiredEntries(now)
	_, staleOk := statsCache["stale"]
	_, keepOk := statsCache["keep"]
	_, emptyOk := statsCache["empty"]
	_, orphanFailOk := lastFailureAt["orphan"]
	_, staleFailOk := lastFailureAt["stale"]
	_, keepFailOk := lastFailureAt["keep"]
	statsMu.Unlock()
	defer ClearAll()

	if !changed {
		t.Fatal("expected prune to report changes")
	}
	if staleOk || emptyOk {
		t.Fatalf("stale/empty should be removed: stale=%v empty=%v", staleOk, emptyOk)
	}
	if !keepOk {
		t.Fatal("keep should remain")
	}
	if orphanFailOk || staleFailOk {
		t.Fatalf("orphan/stale lastFailureAt should be cleared: orphan=%v stale=%v", orphanFailOk, staleFailOk)
	}
	if keepFailOk {
		t.Fatal("keep lastFailureAt past failureMinInterval should be cleared")
	}
}

func TestQueryScoreRowsOrder(t *testing.T) {
	statsMu.Lock()
	statsCache = map[string]proxyConnectivityEntry{
		"low": {
			Days: map[string]dayCounts{
				todayKey(time.Now()): {Success: 2, Failure: 8, DelaySum: 2*200 + 8*5000},
			},
		},
		"high": {
			Days: map[string]dayCounts{
				todayKey(time.Now()): {Success: 45, Failure: 2, DelaySum: 45*200 + 2*5000},
			},
		},
	}
	statsLoaded = true
	statsMu.Unlock()

	rows := QueryScoreRows([]string{"low", "high", "none"})
	if len(rows) != 3 {
		t.Fatalf("len=%d", len(rows))
	}
	if rows[0].Name != "high" {
		t.Fatalf("first=%s want high", rows[0].Name)
	}
	if rows[2].Name != "none" || rows[2].HasStats {
		t.Fatalf("last=%+v want none without stats", rows[2])
	}
	ClearAll()
}

func TestShouldApplyConnectivityOrder(t *testing.T) {
	if shouldApplyConnectivityOrder(map[string]any{"type": "select"}) {
		t.Fatal("select should not apply connectivity order")
	}
	if shouldApplyConnectivityOrder(map[string]any{"type": "Selector"}) {
		t.Fatal("Selector should not apply connectivity order")
	}
	if !shouldApplyConnectivityOrder(map[string]any{"type": "url-test"}) {
		t.Fatal("url-test should apply connectivity order")
	}
	if !shouldApplyConnectivityOrder(map[string]any{"type": "fallback"}) {
		t.Fatal("fallback should apply connectivity order")
	}
	if shouldApplyConnectivityOrder(nil) {
		t.Fatal("nil should not apply connectivity order")
	}
}

func TestSortGroupProxiesFieldSkipsSelector(t *testing.T) {
	group := map[string]any{
		"type":    "select",
		"proxies": []any{"DIRECT", "Auto", "NoHK"},
	}
	sortGroupProxiesField(group)
	list := group["proxies"].([]any)
	if list[0] != "DIRECT" || list[1] != "Auto" || list[2] != "NoHK" {
		t.Fatalf("select proxies reordered: %v", list)
	}
}
