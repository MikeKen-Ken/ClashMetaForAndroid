package tunnel

import (
	"context"
	coreTunnel "github.com/metacubex/mihomo/tunnel"
	"testing"
)

func TestScreenOffKeepsTunnelStateAndPausesOptionalProbes(t *testing.T) {
	previous := connectivityProbesSuspended.Load()
	t.Cleanup(func() { Suspend(previous) })
	before := coreTunnel.Status()
	Suspend(true)
	if coreTunnel.Status() != before || !QueryExplorationMetrics().Paused {
		t.Fatal("screen-off must pause optional exploration without suspending the traffic tunnel")
	}
	counts := QueryExplorationMetrics()
	// A nil adapter would panic if screen-off accidentally allowed a probe.
	probeConnectivityCandidate(context.Background(), connectivityProbeCandidate{})
	if QueryExplorationMetrics().Started != counts.Started {
		t.Fatal("screen-off initiated an optional probe")
	}
	Suspend(false)
	if coreTunnel.Status() != before || QueryExplorationMetrics().Paused {
		t.Fatal("resume failed or changed the traffic tunnel lifecycle")
	}
}
