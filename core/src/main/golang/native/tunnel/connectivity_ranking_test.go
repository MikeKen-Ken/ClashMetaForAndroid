package tunnel

import (
	"reflect"
	"testing"
	"time"

	C "github.com/metacubex/mihomo/constant"
)

func TestRuntimeOrderRequiresTwoDistinctMeasurements(t *testing.T) {
	state := &connectivityOrderState{}
	stamp := time.Unix(100, 0)
	ranks := []connectivityRank{
		{name: "incumbent", cost: 400, tier: 0, evidence: stamp},
		{name: "challenger", cost: 200, tier: 0, evidence: stamp},
	}
	want := []string{"incumbent", "challenger"}
	for i := 0; i < 5; i++ {
		if got := state.order(append([]connectivityRank(nil), ranks...)); !reflect.DeepEqual(got, want) {
			t.Fatalf("re-reading the same results advanced hysteresis: %v", got)
		}
	}
	ranks[1].evidence = stamp.Add(time.Minute)
	if got := state.order(ranks); !reflect.DeepEqual(got, []string{"challenger", "incumbent"}) {
		t.Fatalf("second observation did not promote: %v", got)
	}
}

func TestRuntimeOrderPreservesNearTiesAndResetsLostAdvantage(t *testing.T) {
	state := &connectivityOrderState{}
	stamp := time.Unix(100, 0)
	for i, cost := range []float64{200, 395, 200} {
		ranks := []connectivityRank{
			{name: "a", cost: 400, tier: 0},
			{name: "b", cost: cost, tier: 0, evidence: stamp.Add(time.Duration(i) * time.Minute)},
		}
		if got := state.order(ranks); got[0] != "a" {
			t.Fatalf("nonconsecutive evidence promoted: %v", got)
		}
	}
}

func TestRuntimeOrderHoldsDuringOutageAndClearsChallenges(t *testing.T) {
	state := &connectivityOrderState{}
	stamp := time.Unix(100, 0)
	for round := 0; round < 3; round++ {
		ranks := []connectivityRank{
			{name: "a", cost: 900, tier: 1, evidence: stamp},
			{name: "b", cost: 100, tier: 1, evidence: stamp.Add(time.Duration(round) * time.Minute)},
		}
		if got := state.order(ranks); got[0] != "a" {
			t.Fatalf("outage reordered: %v", got)
		}
	}
	if len(state.challenges) != 0 {
		t.Fatal("outage observations counted toward promotion")
	}
}

func TestRuntimeOrderDropsRemovedNodeChallenges(t *testing.T) {
	state := &connectivityOrderState{challenges: map[string]rankChallenge{"removed": {rounds: 1}}}
	state.order([]connectivityRank{{name: "present", tier: 0}})
	if len(state.challenges) != 0 {
		t.Fatal("removed node retained hysteresis state")
	}
}

func TestRuntimeOrderBypassesHysteresisForHealthAndProtectsHealthy(t *testing.T) {
	state := &connectivityOrderState{}
	ranks := []connectivityRank{
		{name: "failed", cost: 50, tier: 2},
		{name: "unknown", cost: 60, tier: 1},
		{name: "recovered", cost: 900, tier: 0},
	}
	if got := state.order(ranks); !reflect.DeepEqual(got, []string{"recovered", "unknown", "failed"}) {
		t.Fatalf("availability must precede historical latency: %v", got)
	}
}

type rankingHistoryProxy struct {
	delayHistoryProxy
	name string
}

func (p rankingHistoryProxy) Name() string { return p.name }

func TestRecentHealthRequiresMatchingURLAndPeerEvidence(t *testing.T) {
	now := time.Now()
	makeProxy := func(name string, history []C.DelayHistory) C.Proxy {
		return rankingHistoryProxy{name: name, delayHistoryProxy: delayHistoryProxy{histories: map[string]C.ProxyState{
			"target": {History: history},
			"other":  {History: []C.DelayHistory{{Time: now, Delay: 20}}},
		}}}
	}
	failed := makeProxy("failed", []C.DelayHistory{{Time: now.Add(-time.Minute), Delay: 0}, {Time: now, Delay: 0}})
	stale := makeProxy("stale", []C.DelayHistory{{Time: now.Add(-time.Hour), Delay: 10}})
	ranks := connectivityRanks([]C.Proxy{failed, stale}, "target", now)
	if ranks[0].tier != 1 || ranks[1].tier != 1 {
		t.Fatalf("outage/stale evidence produced a verdict: %+v", ranks)
	}
	healthy := makeProxy("healthy", []C.DelayHistory{{Time: now.Add(-time.Second), Delay: 200}})
	ranks = connectivityRanks([]C.Proxy{failed, stale, healthy}, "target", now)
	if ranks[0].tier != 2 || ranks[2].tier != 0 {
		t.Fatalf("fresh peer success not reflected: %+v", ranks)
	}
	// Once this node passes, historical failures do not keep it in the failed tier.
	recovered := makeProxy("failed", []C.DelayHistory{{Time: now.Add(-time.Minute)}, {Time: now, Delay: 200}})
	if got := connectivityRanks([]C.Proxy{recovered}, "target", now)[0].tier; got != 0 {
		t.Fatalf("recovered tier=%d", got)
	}
}
