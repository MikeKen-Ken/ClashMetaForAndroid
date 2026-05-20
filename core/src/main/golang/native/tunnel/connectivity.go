package tunnel

import (
	"context"
	"sync"
	"time"

	"github.com/metacubex/mihomo/adapter/outboundgroup"
	pvd "github.com/metacubex/mihomo/adapter/provider"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/constant/provider"
	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel"
)

var skipDelayCheckGroups = map[string]struct{}{
	"⬆️": {},
	"↩️": {},
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

	// 测速时清空手动选择状态
	if clearable, ok := p.Adapter().(outboundgroup.ClearManualSelectionAble); ok {
		clearable.ClearManualSelection()
	}

	timeout := time.Duration(timeoutMs) * time.Millisecond

	if concurrency <= 0 {
		concurrency = pvd.EffectiveHealthCheckWorkerLimit()
	}
	sem := make(chan struct{}, concurrency)

	wg := &sync.WaitGroup{}

	for _, pr := range g.Providers() {
		wg.Add(1)

		go func(prov provider.ProxyProvider) {
			defer wg.Done()

			testURL := prov.HealthCheckURL()
			if testURL == "" {
				testURL = "https://www.gstatic.com/generate_204"
			}

			proxies := prov.Proxies()
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

					_, _ = proxy.URLTest(ctx, testURL, nil)
				}(px)
			}

			innerWg.Wait()
		}(pr)
	}

	wg.Wait()
	resetGroupConnectTimes(g)
}

func resetGroupConnectTimes(group outboundgroup.ProxyGroup) {
	if connectable, ok := group.(outboundgroup.ConnectTimesAble); ok {
		connectable.ResetConnectTimes()
	}
}

// ClearAllManualSelections clears manual selection on all groups (Selector/Fallback).
// Call when proxy stops or restarts so no node is shown as "current" until user selects again.
func ClearAllManualSelections() {
	for _, name := range QueryProxyGroupNames(false) {
		_ = ClearManualSelectionForGroup(name)
	}
}

// ClearManualSelectionForGroup clears manual selection for a single proxy group (Selector/Fallback).
// Returns true if the group exists and ClearManualSelection was applied.
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

// SetHealthCheckWorkerLimit 同步延迟测速并发上限（订阅健康检查 / 默认组测速回退值）。
func SetHealthCheckWorkerLimit(n int) {
	pvd.SetHealthCheckWorkerLimit(n)
}
