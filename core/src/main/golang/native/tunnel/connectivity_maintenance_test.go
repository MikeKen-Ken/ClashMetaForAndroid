package tunnel

import "testing"

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
