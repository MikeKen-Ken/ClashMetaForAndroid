package tunnel

import (
	"context"
	"fmt"
	"testing"
	"time"

	pvd "github.com/metacubex/mihomo/adapter/provider"
	"github.com/metacubex/mihomo/common/utils"
	C "github.com/metacubex/mihomo/constant"
)

func TestExplorationBudgetRotationAndCooldown(t *testing.T) {
	now := time.Unix(1000, 0)
	attempts := map[string]time.Time{"removed": now}
	var candidates []connectivityProbeCandidate
	for i := 0; i < 7; i++ {
		candidates = append(candidates, connectivityProbeCandidate{key: fmt.Sprint(i)})
	}
	seen := map[string]bool{}
	for round := 0; round < 4; round++ {
		selected := chooseConnectivityProbes(candidates, attempts, now.Add(time.Duration(round)*time.Minute))
		if len(selected) > 2 {
			t.Fatal("exceeded global budget")
		}
		for _, c := range selected {
			if seen[c.key] {
				t.Fatalf("repeated a node before reaching unknown tail: %s", c.key)
			}
			seen[c.key] = true
		}
	}
	if len(seen) != 7 {
		t.Fatalf("starved tail: %v", seen)
	}
	if _, exists := attempts["removed"]; exists {
		t.Fatal("retained deleted profile candidate")
	}
	if got := chooseConnectivityProbes(candidates, attempts, now.Add(4*time.Minute)); len(got) != 0 {
		t.Fatalf("ignored cooldown: %v", got)
	}
	if got := chooseConnectivityProbes(candidates, attempts, now.Add(5*time.Minute)); len(got) != 2 {
		t.Fatalf("nodes did not become eligible for recovery probes: %v", got)
	}
}

func TestExplorationSkipsFreshProviderTests(t *testing.T) {
	now := time.Now()
	candidates := []connectivityProbeCandidate{
		{key: "fresh", lastTest: now},
		{key: "old-failed", lastTest: now.Add(-time.Hour)},
		{key: "unknown"},
	}
	got := chooseConnectivityProbes(candidates, map[string]time.Time{}, now)
	if len(got) != 2 || got[0].key != "unknown" || got[1].key != "old-failed" {
		t.Fatalf("selection=%v", got)
	}
}

func TestCanceledProbeDoesNotCallAdapter(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	// A nil adapter would panic if cancellation were ignored.
	probeConnectivityCandidate(ctx, connectivityProbeCandidate{})
}

func TestMaintenanceStartStopIsIdempotent(t *testing.T) {
	StopConnectivityMaintenance()
	StartConnectivityMaintenance()
	first := connectivityMaintenance.done
	StartConnectivityMaintenance()
	if first != connectivityMaintenance.done {
		t.Fatal("started duplicate loop")
	}
	StopConnectivityMaintenance()
	select {
	case <-first:
	default:
		t.Fatal("stop returned before old loop exited")
	}
	StopConnectivityMaintenance()
}

type cancelableConnectivityProxy struct {
	C.Proxy
	started chan context.Context
}

func (p cancelableConnectivityProxy) URLTest(ctx context.Context, _ string, _ utils.IntRanges[uint16]) (uint16, error) {
	p.started <- ctx
	<-ctx.Done()
	return 0, ctx.Err()
}

func TestProbeCancellationReachesAdapterAndReleasesWorker(t *testing.T) {
	limit := pvd.EffectiveHealthCheckWorkerLimit()
	pvd.SetHealthCheckWorkerLimit(1)
	t.Cleanup(func() { pvd.SetHealthCheckWorkerLimit(limit) })
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	proxy := cancelableConnectivityProxy{started: make(chan context.Context, 1)}
	done := make(chan struct{})
	go func() {
		defer close(done)
		probeConnectivityCandidate(ctx, connectivityProbeCandidate{group: "Auto", proxy: proxy})
	}()
	select {
	case probeCtx := <-proxy.started:
		if C.HealthCheckSourceName(probeCtx) != "Auto" {
			t.Fatal("lost group test ownership")
		}
		deadline, ok := probeCtx.Deadline()
		if !ok || time.Until(deadline) > 5*time.Second {
			t.Fatal("probe deadline is not bounded")
		}
	case <-time.After(time.Second):
		t.Fatal("probe did not start")
	}
	cancel()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("profile cancellation did not stop the probe")
	}
	wait, cancelWait := context.WithTimeout(context.Background(), time.Second)
	defer cancelWait()
	if !pvd.AcquireHealthCheckWorker(wait) {
		t.Fatal("canceled probe leaked the only worker")
	}
	pvd.ReleaseHealthCheckWorker()
}
