package tunnel

import (
	"sort"
	"sync"
	"time"

	"cfa/native/connectivity"
	C "github.com/metacubex/mihomo/constant"
)

const recentHealthWindow = 10 * time.Minute

type connectivityRank struct {
	name         string
	cost         float64
	tier         int // healthy=0, unknown/stale=1, confirmed failure=2
	evidence     time.Time
	failureSince time.Time
}

// Only this group's URL supplies current availability. Historical cost still
// uses the full synced aggregate, without filtering by network or device.
func connectivityRanks(proxies []C.Proxy, url string, now time.Time) []connectivityRank {
	ctx := connectivity.BuildScoreContext()
	ranks := make([]connectivityRank, 0, len(proxies))
	var newestSuccess time.Time
	for _, proxy := range proxies {
		rank := connectivityRank{name: proxy.Name(), cost: ctx.EffectiveDelayFor(proxy.Name()), tier: 1}
		history := proxy.ExtraDelayHistories()[url].History
		// Histories may be provided by adapters in a different order.
		history = append([]C.DelayHistory(nil), history...)
		sort.SliceStable(history, func(i, j int) bool { return history[i].Time.Before(history[j].Time) })
		if n := len(history); n > 0 {
			last := history[n-1]
			rank.evidence = last.Time
			if !last.Time.After(now) && now.Sub(last.Time) <= recentHealthWindow {
				if last.Delay > 0 {
					rank.tier = 0
					if last.Time.After(newestSuccess) {
						newestSuccess = last.Time
					}
				} else if n >= 2 && history[n-2].Delay == 0 &&
					last.Time.Sub(history[n-2].Time) >= time.Second &&
					now.Sub(history[n-2].Time) <= recentHealthWindow {
					rank.failureSince = history[n-2].Time
				}
			}
		}
		ranks = append(ranks, rank)
	}
	for i := range ranks {
		// A success after this failure sequence began is evidence that the
		// network/test endpoint works. A whole-network outage is not a node tier.
		if !ranks[i].failureSince.IsZero() && !newestSuccess.Before(ranks[i].failureSince) {
			ranks[i].tier = 2
		}
	}
	return ranks
}

type rankChallenge struct {
	incumbent string
	evidence  time.Time
	rounds    int
}

type connectivityOrderState struct {
	challenges map[string]rankChallenge
}

var runtimeConnectivityOrder = struct {
	sync.Mutex
	groups map[string]*connectivityOrderState
}{groups: make(map[string]*connectivityOrderState)}

// order uses insertion, not a tolerance-based sort comparator (which would
// violate transitivity). Small improvements preserve the existing runtime order.
// A challenger must beat its predecessor by 10% in two distinct observations.
func (state *connectivityOrderState) order(ranks []connectivityRank) []string {
	if state.challenges == nil {
		state.challenges = make(map[string]rankChallenge)
	}
	present := make(map[string]bool, len(ranks))
	for _, rank := range ranks {
		present[rank.name] = true
	}
	for name := range state.challenges {
		if !present[name] {
			delete(state.challenges, name)
		}
	}
	healthy, observed := false, false
	for _, rank := range ranks {
		healthy = healthy || rank.tier == 0
		observed = observed || !rank.evidence.IsZero()
	}
	// If all current results fail or have expired, keep the routing order until
	// a peer proves connectivity again. Repeated outage rounds are not evidence
	// that a different historical cost should displace the incumbent.
	if observed && !healthy {
		clear(state.challenges)
		names := make([]string, len(ranks))
		for i, rank := range ranks {
			names[i] = rank.name
		}
		return names
	}
	for i := 1; i < len(ranks); i++ {
		candidate := ranks[i]
		previous := ranks[i-1]
		immediate := candidate.tier < previous.tier
		better := candidate.tier == previous.tier && candidate.cost < previous.cost*0.90
		ready := immediate
		if better && !candidate.evidence.IsZero() {
			challenge := state.challenges[candidate.name]
			if challenge.incumbent != previous.name {
				challenge = rankChallenge{incumbent: previous.name}
			}
			if candidate.evidence.After(challenge.evidence) &&
				(challenge.evidence.IsZero() || candidate.evidence.Sub(challenge.evidence) >= time.Second) {
				challenge.evidence = candidate.evidence
				challenge.rounds++
			}
			state.challenges[candidate.name] = challenge
			ready = challenge.rounds >= 2
		} else {
			delete(state.challenges, candidate.name)
		}
		if !ready {
			continue
		}
		j := i
		for j > 0 {
			other := ranks[j-1]
			if candidate.tier > other.tier || (candidate.tier == other.tier &&
				(immediate || candidate.cost >= other.cost*0.90)) {
				break
			}
			ranks[j] = other
			j--
		}
		ranks[j] = candidate
		delete(state.challenges, candidate.name)
	}
	names := make([]string, len(ranks))
	for i, rank := range ranks {
		names[i] = rank.name
	}
	return names
}

func stableRuntimeConnectivityOrder(group string, proxies []C.Proxy, url string) []string {
	state := runtimeConnectivityOrder.groups[group]
	if state == nil {
		state = &connectivityOrderState{}
		runtimeConnectivityOrder.groups[group] = state
	}
	return state.order(connectivityRanks(proxies, url, time.Now()))
}
