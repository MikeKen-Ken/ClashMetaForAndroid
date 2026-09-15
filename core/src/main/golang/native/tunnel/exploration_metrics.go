package tunnel

import "sync/atomic"

var explorationStarted atomic.Uint64
var explorationCompleted atomic.Uint64
var explorationSkipped atomic.Uint64

type ExplorationMetrics struct {
	Paused       bool   `json:"paused"`
	Started      uint64 `json:"started"`
	Completed    uint64 `json:"completed"`
	SkippedTicks uint64 `json:"skippedTicks"`
}

// Lifetime counters only; no node names, queues or persistent history.
func QueryExplorationMetrics() ExplorationMetrics {
	return ExplorationMetrics{connectivityProbesSuspended.Load(), explorationStarted.Load(),
		explorationCompleted.Load(), explorationSkipped.Load()}
}
