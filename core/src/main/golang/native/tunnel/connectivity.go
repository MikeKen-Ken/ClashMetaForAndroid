package tunnel

import (
	"sync"

	"github.com/metacubex/mihomo/adapter/outboundgroup"
	"github.com/metacubex/mihomo/constant/provider"
	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel"
)

func HealthCheck(name string) {
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
}

func HealthCheckAll() {
	for _, g := range QueryProxyGroupNames(false) {
		go func(group string) {
			HealthCheck(group)
		}(g)
	}
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
