package tunnel

import (
	"context"
	"sync"
	"sync/atomic"
	"time"

	coreTunnel "github.com/metacubex/mihomo/tunnel"
)

// Wake often enough for the optional current-node timer to notice it is due.
const connectivityProbeInterval = time.Minute

var connectivityMaintenance = struct {
	sync.Mutex
	cancel context.CancelFunc
	done   chan struct{}
}{}

var connectivityProbesSuspended atomic.Bool

// Stop before replacing adapters. Waiting for cancellation prevents an old
// profile's current-node timer from probing the new profile.
func StopConnectivityMaintenance() {
	connectivityMaintenance.Lock()
	defer connectivityMaintenance.Unlock()
	if connectivityMaintenance.cancel != nil {
		connectivityMaintenance.cancel()
		<-connectivityMaintenance.done
		connectivityMaintenance.cancel = nil
	}
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
		for {
			select {
			case <-ctx.Done():
				return
			case now := <-ticker.C:
				if connectivityProbesSuspended.Load() || coreTunnel.Status() != coreTunnel.Running {
					explorationSkipped.Add(1)
					continue
				}
				maybeProbeAutoDelay(ctx, now)
			}
		}
	}()
}
