package tunnel

func Suspend(s bool) {
	connectivityProbesSuspended.Store(s)
	// Pause optional exploration on screen-off; normal proxy traffic and
	// provider health checks retain their existing lifecycle.
	//
	// WARNING: don't call core's Tunnel.OnSuspend/OnRunning at here,
	// this will cause the core to stop processing new incoming connections when the screen is locked.
}
