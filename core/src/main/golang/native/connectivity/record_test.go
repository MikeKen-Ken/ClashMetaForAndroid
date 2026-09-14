package connectivity

import (
	"os"
	"testing"
	"time"

	C "github.com/metacubex/mihomo/constant"
)

// Recording/reset tests must never touch the user's core home.
func TestMain(m *testing.M) {
	dir, err := os.MkdirTemp("", "connectivity-tests-")
	if err != nil {
		panic(err)
	}
	C.SetHomeDir(dir)
	code := m.Run()
	_ = os.RemoveAll(dir)
	os.Exit(code)
}

func TestRecordingCountsBothOutcomesDuringBurst(t *testing.T) {
	ClearAll()
	t.Cleanup(ClearAll)
	for i := 0; i < 10; i++ {
		RecordDelayTestResult("mixed", 200, 5000)
		RecordDelayTestResult("mixed", 0, 5000)
	}
	counts := statsCache["mixed"].Days[todayKey(time.Now())]
	if counts.Success != 10 || counts.Failure != 10 || counts.DelaySum != 52000 {
		t.Fatalf("burst must preserve the 50%% success rate: %+v", counts)
	}
	// Raw pooled counters retain every remote sample as well.
	merged := sumStats(statsCache, map[string]proxyConnectivityEntry{
		"mixed": {Days: map[string]dayCounts{todayKey(time.Now()): {Success: 100, DelaySum: 10000}}},
	})
	counts = merged["mixed"].Days[todayKey(time.Now())]
	if counts.Success != 110 || counts.Failure != 10 {
		t.Fatalf("lost shared samples: %+v", counts)
	}
}

func TestRecordingPenaltyIndependentOfDeadline(t *testing.T) {
	ClearAll()
	t.Cleanup(ClearAll)
	RecordDelayTestResult("short", 0, 1000)
	RecordDelayTestResult("long", 0, 10000)
	ctx := BuildScoreContext()
	if ctx.ScoreFor("short") != ctx.ScoreFor("long") {
		t.Fatal("deadline changed failure cost")
	}
	RecordDelayTestResult("boundary", 1000, 1000)
	if counts := statsCache["boundary"].Days[todayKey(time.Now())]; counts.Failure != 1 {
		t.Fatalf("deadline boundary must agree with group test failure: %+v", counts)
	}
}

func TestSkippedObservationsDoNotChangeStats(t *testing.T) {
	ClearAll()
	t.Cleanup(ClearAll)
	RecordDelayTestResult("node", -1, 5000)
	RecordDelayTestResult("node", -2, 5000)
	if len(statsCache) != 0 {
		t.Fatal("skipped tests became observations")
	}
}
