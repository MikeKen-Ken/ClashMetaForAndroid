package tunnel

import (
	"testing"

	coreTunnel "github.com/metacubex/mihomo/tunnel"
)

func TestScreenOffKeepsTunnelStateAndPausesOptionalProbes(t *testing.T) {
	previous := connectivityProbesSuspended.Load()
	t.Cleanup(func() { Suspend(previous) })
	before := coreTunnel.Status()
	Suspend(true)
	if coreTunnel.Status() != before || !QueryExplorationMetrics().Paused {
		t.Fatal("screen-off must pause optional probes without suspending the traffic tunnel")
	}
	Suspend(false)
	if coreTunnel.Status() != before || QueryExplorationMetrics().Paused {
		t.Fatal("resume failed or changed the traffic tunnel lifecycle")
	}
}
