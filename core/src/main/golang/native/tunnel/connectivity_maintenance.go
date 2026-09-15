package tunnel

import (
	"context"
	"sort"
	"sync"
	"sync/atomic"
	"time"

	"github.com/metacubex/mihomo/adapter/outboundgroup"
	pvd "github.com/metacubex/mihomo/adapter/provider"
	"github.com/metacubex/mihomo/common/utils"
	C "github.com/metacubex/mihomo/constant"
	coreTunnel "github.com/metacubex/mihomo/tunnel"
)

const (
	connectivityProbeInterval  = time.Minute
	connectivityProbeCooldown  = 5 * time.Minute
	connectivityProbeBudget    = 2
	connectivityProbeTimeoutMs = 5000
)

var connectivityMaintenance = struct {
	sync.Mutex
	cancel context.CancelFunc
	done   chan struct{}
}{}

var connectivityProbesSuspended atomic.Bool

// Stop before replacing adapters. Waiting for cancellation prevents old-profile
// probes from recording results or reordering the new profile's same-name nodes.
func StopConnectivityMaintenance() {
	connectivityMaintenance.Lock()
	defer connectivityMaintenance.Unlock()
	if connectivityMaintenance.cancel != nil {
		connectivityMaintenance.cancel()
		<-connectivityMaintenance.done
		connectivityMaintenance.cancel = nil
	}
	runtimeConnectivityOrder.Lock()
	runtimeConnectivityOrder.groups = make(map[string]*connectivityOrderState)
	runtimeConnectivityOrder.Unlock()
}

func StartConnectivityMaintenance() {
	connectivityMaintenance.Lock()
	defer connectivityMaintenance.Unlock()
	if connectivityMaintenance.cancel != nil {
		return
	}
	ctx, cancel := context.WithCancel(context.Background())
	connectivityMaintenance.cancel = cancel
	connectivityMaintenance.done = make(chan struct{})
	done := connectivityMaintenance.done
	go func() {
		defer close(done)
		ticker := time.NewTicker(connectivityProbeInterval)
		defer ticker.Stop()
		attempts := make(map[string]time.Time)
		for {
			select {
			case <-ctx.Done():
				return
			case now := <-ticker.C:
				if connectivityProbesSuspended.Load() || coreTunnel.Status() != coreTunnel.Running {
					explorationSkipped.Add(1)
					continue
				}
				candidates := connectivityProbeCandidates()
				selected := chooseConnectivityProbes(candidates, attempts, now)
				var wg sync.WaitGroup
				for _, candidate := range selected {
					wg.Add(1)
					go func(candidate connectivityProbeCandidate) {
						defer wg.Done()
						probeConnectivityCandidate(ctx, candidate)
					}(candidate)
				}
				wg.Wait()
				if ctx.Err() == nil {
					ApplyRuntimeConnectivityOrderAll()
				}
			}
		}
	}()
}

type connectivityProbeCandidate struct {
	key      string
	group    string
	url      string
	expected string
	proxy    C.Proxy
	lastTest time.Time
}

func connectivityProbeCandidates() []connectivityProbeCandidate {
	byKey := make(map[string]connectivityProbeCandidate)
	groups := coreTunnel.Proxies()
	groupNames := make([]string, 0, len(groups))
	for name := range groups {
		groupNames = append(groupNames, name)
	}
	sort.Strings(groupNames)
	for _, name := range groupNames {
		p := groups[name]
		if p == nil || shouldSkipDelayCheckGroup(name) || !shouldApplyRuntimeConnectivityOrder(p.Type()) {
			continue
		}
		group, ok := p.Adapter().(outboundgroup.ProxyGroup)
		if !ok {
			continue
		}
		url, expected := delayTestSpec(group)
		for _, proxy := range effectiveDelayTestMembers(group.Proxies()) {
			if _, nested := proxy.Adapter().(outboundgroup.ProxyGroup); nested {
				continue
			}
			key := proxy.Name() + "\x00" + url + "\x00" + expected
			if _, exists := byKey[key]; exists {
				continue
			}
			candidate := connectivityProbeCandidate{key: key, group: name, url: url, expected: expected, proxy: proxy}
			for _, history := range proxy.ExtraDelayHistories()[url].History {
				if history.Time.After(candidate.lastTest) {
					candidate.lastTest = history.Time
				}
			}
			byKey[key] = candidate
		}
	}
	result := make([]connectivityProbeCandidate, 0, len(byKey))
	for _, candidate := range byKey {
		result = append(result, candidate)
	}
	return result
}

// Oldest-tested-first includes unknown, failed and recovered nodes. A global
// budget bounds added traffic independently of group count. Attempts prevent a
// canceled/invalid candidate from permanently occupying the exploration slots.
func chooseConnectivityProbes(candidates []connectivityProbeCandidate, attempts map[string]time.Time, now time.Time) []connectivityProbeCandidate {
	present := make(map[string]bool, len(candidates))
	eligible := make([]connectivityProbeCandidate, 0, len(candidates))
	for _, candidate := range candidates {
		present[candidate.key] = true
		if candidate.lastTest.After(now) {
			candidate.lastTest = time.Time{}
		}
		if attempts[candidate.key].After(candidate.lastTest) {
			candidate.lastTest = attempts[candidate.key]
		}
		if candidate.lastTest.IsZero() || now.Sub(candidate.lastTest) >= connectivityProbeCooldown {
			eligible = append(eligible, candidate)
		}
	}
	for key := range attempts {
		if !present[key] {
			delete(attempts, key)
		}
	}
	sort.Slice(eligible, func(i, j int) bool {
		if !eligible[i].lastTest.Equal(eligible[j].lastTest) {
			return eligible[i].lastTest.Before(eligible[j].lastTest)
		}
		return eligible[i].key < eligible[j].key
	})
	if len(eligible) > connectivityProbeBudget {
		eligible = eligible[:connectivityProbeBudget]
	}
	for _, candidate := range eligible {
		attempts[candidate.key] = now
	}
	return eligible
}

func probeConnectivityCandidate(ctx context.Context, candidate connectivityProbeCandidate) {
	if connectivityProbesSuspended.Load() || ctx.Err() != nil {
		return
	}
	expected, err := utils.NewUnsignedRanges[uint16](candidate.expected)
	if err != nil {
		return
	}
	// The shared semaphore also covers provider and manual tests. Its wait has
	// a deadline so a busy checker cannot stall the maintenance lifecycle.
	waitCtx, cancelWait := context.WithTimeout(ctx, time.Duration(connectivityProbeTimeoutMs)*time.Millisecond)
	defer cancelWait()
	if !pvd.AcquireHealthCheckWorker(waitCtx) {
		return
	}
	defer pvd.ReleaseHealthCheckWorker()
	if ctx.Err() != nil || connectivityProbesSuspended.Load() {
		return
	}
	// Queueing time is not node latency. Give the probe its full deadline after
	// acquiring capacity, while retaining profile-shutdown cancellation.
	ctx, cancel := context.WithTimeout(ctx, time.Duration(connectivityProbeTimeoutMs)*time.Millisecond)
	defer cancel()
	ctx = C.WithHealthCheckSourceName(ctx, candidate.group)
	explorationStarted.Add(1)
	defer explorationCompleted.Add(1)
	_, _ = candidate.proxy.URLTest(ctx, candidate.url, expected)
}
