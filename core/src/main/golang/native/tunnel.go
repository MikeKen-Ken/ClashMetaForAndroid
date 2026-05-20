package main

//#include "bridge.h"
import "C"

import (
	"unsafe"
	"strings"

	"cfa/native/app"
	"cfa/native/tunnel"

	ncfg "cfa/native/config"

	"github.com/metacubex/mihomo/hub/executor"
)

//export queryTunnelState
func queryTunnelState() *C.char {
	mode := tunnel.QueryMode()

	response := &struct {
		Mode string `json:"mode"`
	}{mode}

	return marshalJson(response)
}

//export queryNow
func queryNow(upload, download *C.uint64_t) {
	up, down := tunnel.Now()

	*upload = C.uint64_t(up)
	*download = C.uint64_t(down)
}

//export queryTotal
func queryTotal(upload, download *C.uint64_t) {
	up, down := tunnel.Total()

	*upload = C.uint64_t(up)
	*download = C.uint64_t(down)
}

//export queryGroupNames
func queryGroupNames(excludeNotSelectable C.int) *C.char {
	return marshalJson(tunnel.QueryProxyGroupNames(excludeNotSelectable != 0))
}

//export queryGroup
func queryGroup(name C.c_string, sortMode C.c_string) *C.char {
	n := C.GoString(name)
	s := C.GoString(sortMode)

	mode := tunnel.Default

	switch s {
	case "Title":
		mode = tunnel.Title
	case "Delay":
		mode = tunnel.Delay
	}

	response := tunnel.QueryProxyGroup(n, mode, app.SubtitlePattern())

	if response == nil {
		return nil
	}

	return marshalJson(response)
}

//export healthCheck
func healthCheck(completable unsafe.Pointer, name C.c_string) {
	go func(name string) {
		tunnel.HealthCheck(name)

		C.complete(completable, nil)
	}(C.GoString(name))
}

//export healthCheckWithTimeout
func healthCheckWithTimeout(completable unsafe.Pointer, name C.c_string, timeoutMs C.int, concurrency C.int) {
	go func(name string, timeout int, conc int) {
		tunnel.HealthCheckWithTimeout(name, timeout, conc)

		C.complete(completable, nil)
	}(C.GoString(name), int(timeoutMs), int(concurrency))
}

//export healthCheckAll
func healthCheckAll() {
	tunnel.HealthCheckAll()
}

//export setHealthCheckWorkerLimit
func setHealthCheckWorkerLimit(limit C.int) {
	tunnel.SetHealthCheckWorkerLimit(int(limit))
}

//export patchRuntimeLogLevel
func patchRuntimeLogLevel(level C.c_string) C.int {
	if executor.PatchRuntimeLogLevel(strings.ToLower(C.GoString(level))) {
		return 1
	}

	return 0
}



//export patchConnectivityJson
func patchConnectivityJson(p *C.char) C.int {
	if ncfg.ApplyConnectivityPatchFromJSON(C.GoString(p)) {
		return 1
}

	return 0
}

//export patchSelector
func patchSelector(selector, name C.c_string) C.int {
	s := C.GoString(selector)
	n := C.GoString(name)

	if tunnel.PatchSelector(s, n) {
		return 1
	}

	return 0
}

//export clearAllManualSelections
func clearAllManualSelections() {
	tunnel.ClearAllManualSelections()
}

//export clearManualSelectionForGroup
func clearManualSelectionForGroup(name C.c_string) C.int {
	if tunnel.ClearManualSelectionForGroup(C.GoString(name)) {
		return 1
	}
	return 0
}

//export queryProviders
func queryProviders() *C.char {
	return marshalJson(tunnel.QueryProviders())
}

//export queryConnections
func queryConnections() *C.char {
	return marshalJson(queryConnectionsSnapshot())
}

//export closeConnection
func closeConnection(id C.c_string) C.int {
	if closeConnectionByID(C.GoString(id)) {
		return 1
	}
	return 0
}

//export closeAllConnectionsExported
func closeAllConnectionsExported() {
	closeAllConnections()
}

//export closeConnectionsExcludingDirectExported
func closeConnectionsExcludingDirectExported() {
	closeConnectionsExcludingDirect()
}

//export closeConnectionsUsingProxyGroupExported
func closeConnectionsUsingProxyGroupExported(name C.c_string) {
	closeConnectionsUsingProxyGroup(C.GoString(name))
}

//export closeLanConnectionsExported
func closeLanConnectionsExported() {
	closeLanConnections()
}

//export updateProvider
func updateProvider(completable unsafe.Pointer, pType C.c_string, name C.c_string) {
	go func(pType, name string) {
		C.complete(completable, marshalString(tunnel.UpdateProvider(pType, name)))

		C.release_object(completable)
	}(C.GoString(pType), C.GoString(name))
}

//export suspend
func suspend(suspended C.int) {
	tunnel.Suspend(suspended != 0)
}
