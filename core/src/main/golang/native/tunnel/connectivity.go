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
// group using the URL owned by that group. The timeout is applied independently
// to each network phase; ElapsedMs is the whole group operation duration.
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
	if concurrency <= 0 {
		concurrency = 1
	}
	if concurrency > result.Tested {
		concurrency = result.Tested
	}
	sem := make(chan struct{}, concurrency)
	var succeeded atomic.Int64
	var wg sync.WaitGroup

	for _, px := range sortProxiesByConnectivityScore(members) {
		wg.Add(1)
		sem <- struct{}{}
		go func(proxy C.Proxy) {
			defer wg.Done()
			defer func() { <-sem }()

			ctx := C.WithHealthCheckSourceName(context.Background(), name)
			ctx = C.WithDelayTestTimeoutMs(ctx, timeoutMs)
			delay, testErr := proxy.URLTest(ctx, testURL, expectedStatus)
			if testErr != nil || delay == 0 || int(delay) >= timeoutMs {
				log.Debugln("[delay-test] group=%s proxy=%s url=%s timeoutMs=%d failed: %v", name, proxy.Name(), testURL, timeoutMs, testErr)
				return
			}
			succeeded.Add(1)
		}(px)
	}

	wg.Wait()
	result.Succeeded = int(succeeded.Load())
	result.Failed = result.Tested - result.Succeeded
	resetGroupConnectTimes(g)
	if result.Succeeded == 0 {
		result.Error = "all proxies timed out or failed"
		return result
	}

	// Routing state is changed only after at least one effective member passed.
	if clearable, ok := p.Adapter().(outboundgroup.ClearManualSelectionAble); ok {
		clearable.ClearManualSelection()
	}
	ApplyRuntimeConnectivityOrderAll()
	applyPostReorderAutoGroupSelection(p)
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

func firstAliveProxyName(proxies []C.Proxy, testURL string) string {
	for _, px := range proxies {
		if px == nil {
			continue
		}
		name := px.Name()
		if isSkipLeafProxyName(name) {
			continue
		}
		if px.AliveForTestUrl(testURL) {
			return name
		}
	}
	return ""
}

// applyPostReorderAutoGroupSelection keeps url-test on the first currently
// alive score-ordered node (a real pin). Fallback walks the reordered list,
// so it only needs the pin cleared.
func applyPostReorderAutoGroupSelection(p C.Proxy) {
	if p == nil {
		return
	}
	adapter := p.Adapter()
	switch adapter.Type() {
	case C.URLTest:
		selectable, ok := adapter.(outboundgroup.SelectAble)
		group, okGroup := adapter.(outboundgroup.ProxyGroup)
		if !ok || !okGroup {
			return
		}
		testURL, _ := delayTestSpec(group)
		if name := firstAliveProxyName(group.Proxies(), testURL); name != "" {
			selectable.ForceSet(name)
			log.Infoln("[delay-test] %s pin first-score alive %s", p.Name(), name)
		}
	case C.Fallback:
		if clearable, ok := adapter.(outboundgroup.ClearManualSelectionAble); ok {
			clearable.ClearManualSelection()
		}
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

// ClearAllManualSelections clears manual selection on all groups (Selector/Fallback).
// Call when proxy stops or restarts so no node is shown as "current" until user selects again.
func ClearAllManualSelections() {
	for _, name := range QueryProxyGroupNames(false) {
		p := tunnel.Proxies()[name]
		if p == nil {
			continue
		}
		if clearable, ok := p.Adapter().(outboundgroup.ClearManualSelectionAble); ok {
			clearable.ClearManualSelection()
		}
	}
}

// ClearManualSelectionForGroup clears manual selection on a single group (Selector/Fallback).
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

// ApplyRuntimeConnectivityOrder 按最新联通评分重排 url-test / fallback 组的运行时节点列表，
// 不清整包 ApplyConfig。测速后 Fallback 会自动使用评分第一且当前可用的节点。
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
	names := make([]string, len(proxies))
	for i, px := range proxies {
		names[i] = px.Name()
	}
	sorted := connectivity.SortNamesByConnectivity(names)
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
	for name, p := range tunnel.Proxies() {
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

// ShouldSkipPersistedAutoGroupSelection 启动时不把上次固定写回 url-test/fallback。
func ShouldSkipPersistedAutoGroupSelection(name string) bool {
	if shouldSkipDelayCheckGroup(name) {
		return true
	}
	p := tunnel.Proxies()[name]
	if p == nil {
		return false
	}
	return isAutoSelectAdapterType(p.Type())
}

// ApplyStartupAutoGroupOrder 启动/重载后按积分重排自动选组。
// url-test 钉在当前第一个可用节点；fallback 清钉后走重排列表。
func ApplyStartupAutoGroupOrder() {
	ApplyRuntimeConnectivityOrderAll()
	for name, p := range tunnel.Proxies() {
		if p == nil || shouldSkipDelayCheckGroup(name) {
			continue
		}
		if !isAutoSelectAdapterType(p.Type()) {
			continue
		}
		applyPostReorderAutoGroupSelection(p)
	}
}

// SetHealthCheckWorkerLimit 同步延迟测速并发上限（订阅健康检查 / 默认组测速回退值）。
func SetHealthCheckWorkerLimit(n int) {
	pvd.SetHealthCheckWorkerLimit(n)
}
