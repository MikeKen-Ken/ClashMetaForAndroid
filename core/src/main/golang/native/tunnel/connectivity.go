package tunnel

import (
	"context"
	"sync"
	"time"

	"cfa/native/connectivity"

	"github.com/metacubex/mihomo/adapter/outboundgroup"
	pvd "github.com/metacubex/mihomo/adapter/provider"
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

	// 测速时清空手动选择状态，使所有节点都不再是“手动选择”
	if clearable, ok := p.Adapter().(outboundgroup.ClearManualSelectionAble); ok {
		clearable.ClearManualSelection()
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

// HealthCheckWithTimeout 使用自定义超时时间（毫秒）和并发节点数对指定代理组执行健康检查。
// concurrency 控制同时测速的节点数上限，与原始 errgroup.SetLimit(N) 等价。
func HealthCheckWithTimeout(name string, timeoutMs int, concurrency int) {
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

	// 测速时清空手动选择，并先按当前积分重排，使已通过节点能立刻按顺序被选中。
	if clearable, ok := p.Adapter().(outboundgroup.ClearManualSelectionAble); ok {
		clearable.ClearManualSelection()
	}
	ApplyRuntimeConnectivityOrderAll()

	timeout := time.Duration(timeoutMs) * time.Millisecond

	if concurrency <= 0 {
		concurrency = pvd.EffectiveHealthCheckWorkerLimit()
	}
	sem := make(chan struct{}, concurrency)

	var picker *scoreEarlyPicker
	if selectable, ok := p.Adapter().(outboundgroup.SelectAble); ok {
		picker = newScoreEarlyPicker(name, g.Proxies(), selectable)
	}

	wg := &sync.WaitGroup{}

	for _, pr := range g.Providers() {
		wg.Add(1)

		go func(prov provider.ProxyProvider) {
			defer wg.Done()

			testURL := prov.HealthCheckURL()
			if testURL == "" {
				testURL = "https://www.gstatic.com/generate_204"
			}

			proxies := sortProxiesByConnectivityScore(prov.Proxies())
			innerWg := &sync.WaitGroup{}

			for _, px := range proxies {
				innerWg.Add(1)

				sem <- struct{}{} // 获取令牌，限流并发

				go func(proxy C.Proxy) {
					defer innerWg.Done()
					defer func() { <-sem }() // 释放令牌

					ctx, cancel := context.WithTimeout(context.Background(), timeout)
					defer cancel()
					ctx = C.WithHealthCheckSourceName(ctx, name)

					delay, _ := proxy.URLTest(ctx, testURL, nil)
					if picker != nil {
						picker.onResult(proxy.Name(), int(delay), timeoutMs)
					}
				}(px)
			}

			innerWg.Wait()
		}(pr)
	}

	wg.Wait()
	if picker != nil {
		picker.stop()
	}
	resetGroupConnectTimes(g)
	ApplyRuntimeConnectivityOrderAll()
	if clearable, ok := p.Adapter().(outboundgroup.ClearManualSelectionAble); ok {
		clearable.ClearManualSelection()
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

type scoreEarlyPicker struct {
	mu         sync.Mutex
	stopped    bool
	group      string
	order      []string
	passed     map[string]struct{}
	best       string
	selectable outboundgroup.SelectAble
}

func newScoreEarlyPicker(group string, members []C.Proxy, selectable outboundgroup.SelectAble) *scoreEarlyPicker {
	names := make([]string, 0, len(members))
	for _, px := range members {
		if px == nil {
			continue
		}
		name := px.Name()
		if name == "" || name == "DIRECT" || name == "REJECT" {
			continue
		}
		names = append(names, name)
	}
	return &scoreEarlyPicker{
		group:      group,
		order:      connectivity.SortNamesByConnectivity(names),
		passed:     make(map[string]struct{}),
		selectable: selectable,
	}
}

func (p *scoreEarlyPicker) stop() {
	if p == nil {
		return
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	p.stopped = true
}

func (p *scoreEarlyPicker) onResult(name string, delay int, timeoutMs int) {
	if p == nil || p.selectable == nil || name == "" || name == "DIRECT" || name == "REJECT" {
		return
	}
	if delay <= 0 || timeoutMs <= 0 || delay > timeoutMs {
		return
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.stopped {
		return
	}
	p.passed[name] = struct{}{}
	best := ""
	for _, candidate := range p.order {
		if _, ok := p.passed[candidate]; ok {
			best = candidate
			break
		}
	}
	if best == "" || best == p.best {
		return
	}
	p.best = best
	p.selectable.ForceSet(best)
	log.Infoln("[score-early-pick] %s -> %s", p.group, best)
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

// ApplyStartupAutoGroupOrder 启动/重载后按积分重排自动选组并清钉，无需先测速。
func ApplyStartupAutoGroupOrder() {
	ApplyRuntimeConnectivityOrderAll()
	for name, p := range tunnel.Proxies() {
		if p == nil || shouldSkipDelayCheckGroup(name) {
			continue
		}
		if !isAutoSelectAdapterType(p.Type()) {
			continue
		}
		if clearable, ok := p.Adapter().(outboundgroup.ClearManualSelectionAble); ok {
			clearable.ClearManualSelection()
		}
	}
}

// SetHealthCheckWorkerLimit 同步延迟测速并发上限（订阅健康检查 / 默认组测速回退值）。
func SetHealthCheckWorkerLimit(n int) {
	pvd.SetHealthCheckWorkerLimit(n)
}
