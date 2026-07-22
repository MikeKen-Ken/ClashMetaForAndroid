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
