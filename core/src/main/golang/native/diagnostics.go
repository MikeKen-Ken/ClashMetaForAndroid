package main

//#include "bridge.h"
import "C"

import (
	"cfa/native/tunnel"
	"github.com/metacubex/mihomo/networkrecovery"
)

//export queryNetworkDiagnostics
func queryNetworkDiagnostics() *C.char {
	return marshalJson(struct {
		networkrecovery.Metrics
		Exploration tunnel.ExplorationMetrics `json:"exploration"`
	}{networkrecovery.Diagnostics(), tunnel.QueryExplorationMetrics()})
}
