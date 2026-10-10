package connectivity

import (
	"math"
	"testing"
)

func TestDisplayScoreLogScale(t *testing.T) {
	cases := []struct {
		delayMs float64
		want    float64
	}{
		{10, 100},
		{50, 100},
		{400, 54.8},
		{2000, 19.9},
		{5000, 0},
		{9000, 0},
		{math.NaN(), 54.8},
	}
	for _, c := range cases {
		if got := displayScoreFromAvgDelay(c.delayMs); math.Abs(got-c.want) > 0.05 {
			t.Errorf("displayScoreFromAvgDelay(%v) = %.2f; want %.1f", c.delayMs, got, c.want)
		}
	}
}

func TestScoreRowsSortBeyondDisplayCeiling(t *testing.T) {
	ClearAll()
	t.Cleanup(ClearAll)
	for i := 0; i < 50; i++ {
		RecordDelayTestResult("dead-short", 0, 6000)
		RecordDelayTestResult("dead-long", 0, 9000)
	}
	rows := QueryScoreRows([]string{"dead-long", "dead-short"})
	if rows[0].Score != 0 || rows[1].Score != 0 {
		t.Fatalf("both nodes are past the display ceiling: %+v", rows)
	}
	if rows[0].Name != "dead-short" {
		t.Fatalf("clamped display score must not decide order: %+v", rows)
	}
}
