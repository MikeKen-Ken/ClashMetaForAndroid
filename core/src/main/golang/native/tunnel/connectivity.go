package tunnel

import (
	"context"
	"sync"
	"sync/atomic"
	"time"

	"cfa/native/connectivity"

	"github.com/metacubex/mihomo/adapter/outboundgroup"
	pvd "github.com/metacubex/mihomo/adapter/provider"
	"github.com/metacubex/mihomo/common/utils"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/constant/provider"
	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel"
)

// 与 config/custom-clash-script.js 中 DIRECT / FALLBACK 输出名一致
var skipDelayCheckGroups = map[string]struct{}{
	"Direct": {},
	"Final":  {},
}

func shouldSkipDelayCheckGroup(name string) bool {
	_, ok := skipDelayCheckGroups[name]
	return ok
}

func HealthCheck(name string) {
	if shouldSkipDelayCheckGroup(name) {
		return
	}

	p := tunnel.Proxies()[name]

	if p == nil {
		log.Warnln("Request health check for `%s`: not found", name)

		return
	}

	g, ok := p.Adapter().(outboundgroup.ProxyGroup)
	if !ok {
		log.Warnln("Request health check for `%s`: invalid type %s", name, p.Type().String())

		return
	}

	ApplyRuntimeConnectivityOrderAll()

	wg := &sync.WaitGroup{}

	for _, pr := range g.Providers() {
		wg.Add(1)

		go func(provider provider.ProxyProvider) {
			provider.HealthCheck()

			wg.Done()
		}(pr)
	}

	wg.Wait()
	resetGroupConnectTimes(g)
	// 测速后按最新积分重排所有 url-test/fallback 组。NoHK / Download 与 Auto
	// 共用节点，只重排当前组时第二、第三组清钉后仍会回到配置原序。
	ApplyRuntimeConnectivityOrderAll()
}

func HealthCheckAll() {
	for _, g := range QueryProxyGroupNames(false) {
		if shouldSkipDelayCheckGroup(g) {
			continue
		}
		go func(group string) {
			HealthCheck(group)
		}(g)
	}
}

type DelayTestResult struct {
	Group     string `json:"group"`
	Tested    int    `json:"tested"`
	Succeeded int    `json:"succeeded"`
	Failed    int    `json:"failed"`
	ElapsedMs int64  `json:"elapsedMs"`
	Error     string `json:"error,omitempty"`
}

// HealthCheckWithTimeout tests only the effective members of the requested
// group using the URL owned by that group. timeoutMs is both the displayed
// delay limit and the per-node URLTest deadline. ElapsedMs is the whole group
// operation duration.
func HealthCheckWithTimeout(name string, timeoutMs int, concurrency int) (result DelayTestResult) {
	startedAt := time.Now()
	result = DelayTestResult{Group: name}
	defer func() {
		result.ElapsedMs = time.Since(startedAt).Milliseconds()
	}()

	if shouldSkipDelayCheckGroup(name) {
		result.Error = "group is excluded from delay testing"
		return result
	}
	if timeoutMs <= 0 {
		result.Error = "timeout must be greater than zero"
		return result
	}

	p := tunnel.Proxies()[name]

	if p == nil {
		log.Warnln("Request health check for `%s`: not found", name)
		result.Error = "proxy group not found"
		return result
	}

	g, ok := p.Adapter().(outboundgroup.ProxyGroup)
	if !ok {
		log.Warnln("Request health check for `%s`: invalid type %s", name, p.Type().String())
		result.Error = "proxy is not a testable group"
		return result
	}

	testURL, expectedStatusText := delayTestSpec(g)
	// A delay test never touches the user's pin.
	expectedStatus, err := utils.NewUnsignedRanges[uint16](expectedStatusText)
	if err != nil {
		log.Debugln("[delay-test] group=%s invalid expected status %q: %v", name, expectedStatusText, err)
		expectedStatus = nil
	}
	members := effectiveDelayTestMembers(g.Proxies())
	result.Tested = len(members)
	if result.Tested == 0 {
		result.Error = "proxy group has no testable members"
		return result
	}

	if concurrency <= 0 {
		concurrency = pvd.EffectiveHealthCheckWorkerLimit()
	}
	concurrency = pvd.ClampHealthCheckWorkerLimit(concurrency)
	if concurrency > result.Tested {
		concurrency = result.Tested
	}
	orderedMembers := sortProxiesByConnectivityScore(members)
	orderedNames := make([]string, len(orderedMembers))
	for i, proxy := range orderedMembers {
		orderedNames[i] = proxy.Name()
	}
	var early *delayTestEarlyOrder
	if shouldApplyRuntimeConnectivityOrder(g.Type()) {
		groupProxy := p
		groupName := name
		early = newDelayTestEarlyOrder(orderedNames, timeoutMs, func(names []string) {
			if tunnel.Proxies()[groupName] != groupProxy {
				return
			}
			if reorder, ok := groupProxy.Adapter().(cachedProxyReorderAble); ok {
				reorder.ReorderCachedProxies(names)
			}
		})
	}

	sem := make(chan struct{}, concurrency)
	var succeeded atomic.Int64
	var wg sync.WaitGroup

	for _, px := range orderedMembers {
		wg.Add(1)
		sem <- struct{}{}
		go func(proxy C.Proxy) {
			defer wg.Done()
			defer func() { <-sem }()

			ctx := C.WithHealthCheckSourceName(context.Background(), name)
			if !pvd.AcquireHealthCheckWorker(ctx) {
				return
			}
			defer pvd.ReleaseHealthCheckWorker()
			ctx = C.WithDelayTestTimeoutMs(ctx, timeoutMs)
			delay, testErr := proxy.URLTest(ctx, testURL, expectedStatus)
			if testErr != nil || delay == 0 || int(delay) >= timeoutMs {
				log.Debugln("[delay-test] group=%s proxy=%s url=%s timeoutMs=%d failed: %v", name, proxy.Name(), testURL, timeoutMs, testErr)
				return
			}
			succeeded.Add(1)
			early.onResult(proxy.Name(), int(delay))
		}(px)
	}

	wg.Wait()
	early.stop()
	result.Succeeded = int(succeeded.Load())
	result.Failed = result.Tested - result.Succeeded
	resetGroupConnectTimes(g)
	if tunnel.Proxies()[name] != p {
		if result.Succeeded == 0 {
			result.Error = "all proxies timed out or failed"
		}
		return result // The profile was replaced while its probes were running.
	}
	if result.Succeeded == 0 {
		result.Error = "all proxies timed out or failed"
		// A finished auto-group test still refreshes the score order.
		if shouldApplyRuntimeConnectivityOrder(g.Type()) {
			ApplyRuntimeConnectivityOrderAll()
		}
		return result
	}

	ApplyRuntimeConnectivityOrderAll()
	return result
}

func delayTestSpec(group outboundgroup.ProxyGroup) (string, string) {
	if spec, ok := group.(outboundgroup.DelayTestSpecAble); ok {
		url, expectedStatus := spec.DelayTestSpec()
		if url != "" {
			return url, expectedStatus
		}
	}
	return C.DefaultTestURL, ""
}

func effectiveDelayTestMembers(proxies []C.Proxy) []C.Proxy {
	result := make([]C.Proxy, 0, len(proxies))
	seen := make(map[string]struct{}, len(proxies))
	for _, proxy := range proxies {
		if proxy == nil || isSkipLeafProxyName(proxy.Name()) {
			continue
		}
		if _, exists := seen[proxy.Name()]; exists {
			continue
		}
		seen[proxy.Name()] = struct{}{}
		result = append(result, proxy)
	}
	return result
}

func isSkipLeafProxyName(name string) bool {
	switch name {
	case "", "DIRECT", "REJECT", "REJECT-DROP", "PASS", "COMPATIBLE":
		return true
	default:
		return false
	}
}

func resetGroupConnectTimes(group outboundgroup.ProxyGroup) {
	if connectable, ok := group.(outboundgroup.ConnectTimesAble); ok {
		connectable.ResetConnectTimes()
	}
}

func sortProxiesByConnectivityScore(proxies []C.Proxy) []C.Proxy {
	if len(proxies) <= 1 {
		return proxies
	}
	names := make([]string, len(proxies))
	byName := make(map[string]C.Proxy, len(proxies))
	for i, px := range proxies {
		name := px.Name()
		names[i] = name
		byName[name] = px
	}
	out := make([]C.Proxy, 0, len(proxies))
	seen := make(map[string]struct{}, len(proxies))
	for _, name := range connectivity.SortNamesByConnectivity(names) {
		px, ok := byName[name]
		if !ok {
			continue
		}
		if _, dup := seen[name]; dup {
			continue
		}
		seen[name] = struct{}{}
		out = append(out, px)
	}
	return out
}

// ClearManualSelectionForGroup releases the pin of a single group. It is the
// only way a pin is released, and is called only for the user's explicit unpin.
func ClearManualSelectionForGroup(name string) bool {
	p := tunnel.Proxies()[name]
	if p == nil {
		return false
	}
	if clearable, ok := p.Adapter().(outboundgroup.ClearManualSelectionAble); ok {
		clearable.ClearManualSelection()
		return true
	}
	return false
}

type cachedProxyReorderAble interface {
	ReorderCachedProxies([]string)
}

func shouldApplyRuntimeConnectivityOrder(adapterType C.AdapterType) bool {
	switch adapterType {
	case C.URLTest, C.Fallback:
		return true
	default:
		return false
	}
}

// ApplyRuntimeConnectivityOrder reorders a url-test / fallback group by the
// same connectivity score the desktop Default view uses: higher score first,
// original order on a tie. It does not reload the profile.
func ApplyRuntimeConnectivityOrder(name string) bool {
	if shouldSkipDelayCheckGroup(name) {
		return false
	}
	p := tunnel.Proxies()[name]
	if p == nil {
		return false
	}
	g, ok := p.Adapter().(outboundgroup.ProxyGroup)
	if !ok {
		return false
	}
	if !shouldApplyRuntimeConnectivityOrder(g.Type()) {
		return false
	}
	proxies := g.Proxies()
	if len(proxies) <= 1 {
		return false
	}
	ordered := sortProxiesByConnectivityScore(proxies)
	sorted := make([]string, len(ordered))
	for i, proxy := range ordered {
		sorted[i] = proxy.Name()
	}
	r, ok := p.Adapter().(cachedProxyReorderAble)
	if !ok {
		log.Warnln("ApplyRuntimeConnectivityOrder `%s`: ReorderCachedProxies not available", name)
		return false
	}
	r.ReorderCachedProxies(sorted)
	return true
}

// ApplyRuntimeConnectivityOrderAll 按最新积分重排全部 url-test / fallback 组。
func ApplyRuntimeConnectivityOrderAll() {
	proxies := tunnel.Proxies()
	for name, p := range proxies {
		if p == nil {
			continue
		}
		if _, ok := p.Adapter().(outboundgroup.ProxyGroup); !ok {
			continue
		}
		ApplyRuntimeConnectivityOrder(name)
	}
}

func isAutoSelectAdapterType(t C.AdapterType) bool {
	switch t {
	case C.URLTest, C.Fallback:
		return true
	default:
		return false
	}
}

// RestoreSelection re-applies a saved pin to select / url-test / fallback groups
// after a (re)load. It does not require the node to be in the group yet (a
// provider may not have loaded), so the pin takes effect as soon as it appears.
// It reports false only when the group does not exist or cannot be pinned; the
// caller must keep the saved record in that case.
func RestoreSelection(group, name string) bool {
	if name == "" {
		return false
	}
	p := tunnel.Proxies()[group]
	if p == nil {
		return false
	}
	selectable, ok := p.Adapter().(outboundgroup.SelectAble)
	if !ok {
		return false
	}
	selectable.ForceSet(name)
	log.Infoln("Restore selection %s -> %s", group, name)
	return true
}

// ApplyStartupAutoGroupOrder 启动/重载后按积分重排自动选组。
// 只改顺序，不创建也不清除用户的固定。
func ApplyStartupAutoGroupOrder() {
	ApplyRuntimeConnectivityOrderAll()
}

// SetHealthCheckWorkerLimit 同步延迟测速并发上限（订阅健康检查 / 默认组测速回退值）。
func SetHealthCheckWorkerLimit(n int) {
	pvd.SetHealthCheckWorkerLimit(n)
}
