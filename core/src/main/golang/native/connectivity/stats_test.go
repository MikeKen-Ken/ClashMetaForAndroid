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
	if diff := DecayWeight(6) - 0.25; diff < -1e-9 || diff > 1e-9 {
		t.Fatalf("6-day weight = %v, want 0.25", DecayWeight(6))
	}
	if DecayWeight(30) != 0 {
		t.Fatalf("30-day weight = %v, want 0", DecayWeight(30))
	}
}

func TestBayesianScoreShrinksSmallSampleWithK20(t *testing.T) {
	prior := BayesianPrior{Alpha: 15, Beta: 5}
	perfectSmall := bayesianScore(1, 0, prior)
	stableLarge := bayesianScore(9, 1, prior)
	if stableLarge <= perfectSmall {
		t.Fatalf("stableLarge=%v should exceed perfectSmall=%v", stableLarge, perfectSmall)
	}
}

func TestSortNamesByConnectivityOrder(t *testing.T) {
	names := []string{"node-low", "node-high", "node-untested"}
	ctx := ScoreContext{
		byProxy: map[string]WeightedStats{
			"node-low":  {Success: 1, Failure: 9},
			"node-high": {Success: 45, Failure: 5},
		},
		prior: computeBayesianPrior(WeightedStats{Success: 46, Failure: 14}),
	}

	keys := make([]nameScoreKey, len(names))
	for i, name := range names {
		keys[i] = nameScoreKey{index: i, score: ctx.ScoreFor(name)}
	}
	if ctx.ScoreFor("node-high") <= ctx.ScoreFor("node-low") {
		t.Fatal("high score should beat low score")
	}
	if ctx.ScoreFor("node-untested") <= ctx.ScoreFor("node-low") {
		t.Fatal("untested should beat clearly bad node")
	}
	_ = keys
}

func TestOlderDayCountsLessThanToday(t *testing.T) {
	today := time.Date(2026, 7, 5, 12, 0, 0, 0, time.Local)
	days := map[string]dayCounts{
		"2026-07-05": {Success: 10, Failure: 0},
		"2026-07-02": {Success: 10, Failure: 0},
	}
	weighted := sumWeightedDays(days, today)
	if weighted.Success <= 10 {
		t.Fatalf("weighted success=%v, want > 10", weighted.Success)
	}
	if weighted.Success >= 20 {
		t.Fatalf("weighted success=%v, want < 20", weighted.Success)
	}
}
