package tunnel

import (
	"context"
	"sort"
	"strings"
	"sync/atomic"
	"time"

	"github.com/metacubex/mihomo/adapter/outboundgroup"
	pvd "github.com/metacubex/mihomo/adapter/provider"
	"github.com/metacubex/mihomo/common/utils"
	C "github.com/metacubex/mihomo/constant"
	coreTunnel "github.com/metacubex/mihomo/tunnel"
)

// Optional current-leaf probe. Default off, default every 5 minutes.
// Desktop uses the same switch and interval. It does not reorder groups.
var (
	autoDelayEnabled atomic.Bool
	autoDelayMinutes atomic.Int32
	autoDelayNextDue time.Time
)

func SetAutoDelayDetection(enabled bool, minutes int) {
	if minutes <= 0 {
		minutes = 5
	}
	autoDelayMinutes.Store(int32(minutes))
	autoDelayEnabled.Store(enabled)
}

func maybeProbeAutoDelay(ctx context.Context, now time.Time) {
	if !autoDelayEnabled.Load() {
		autoDelayNextDue = time.Time{}
		return
	}
	if connectivityProbesSuspended.Load() || coreTunnel.Status() != coreTunnel.Running || ctx.Err() != nil {
		return
	}
	minutes := int(autoDelayMinutes.Load())
	if minutes <= 0 {
		minutes = 5
	}
	if autoDelayNextDue.IsZero() {
		autoDelayNextDue = now.Add(time.Duration(minutes) * time.Minute)
		return
	}
	if now.Before(autoDelayNextDue) {
		return
	}
	autoDelayNextDue = now.Add(time.Duration(minutes) * time.Minute)
	probeAutoDelayLeaf(ctx)
}

func probeAutoDelayLeaf(ctx context.Context) {
	groupName, proxy, url, expectedText, timeoutMs, ok := autoDelayLeaf()
	if !ok || ctx.Err() != nil {
		return
	}
	expected, err := utils.NewUnsignedRanges[uint16](expectedText)
	if err != nil {
		expected = nil
	}
	waitCtx, cancelWait := context.WithTimeout(ctx, time.Duration(timeoutMs)*time.Millisecond)
	defer cancelWait()
	if !pvd.AcquireHealthCheckWorker(waitCtx) {
		return
	}
	defer pvd.ReleaseHealthCheckWorker()
	if ctx.Err() != nil || connectivityProbesSuspended.Load() {
		return
	}
	probeCtx, cancel := context.WithTimeout(ctx, time.Duration(timeoutMs)*time.Millisecond)
	defer cancel()
	probeCtx = C.WithHealthCheckSourceName(probeCtx, groupName)
	probeCtx = C.WithDelayTestTimeoutMs(probeCtx, timeoutMs)
	_, _ = proxy.URLTest(probeCtx, url, expected)
	notifyStartupGroupRefresh(groupName)
}

func autoDelayLeaf() (string, C.Proxy, string, string, int, bool) {
	if coreTunnel.Mode() == coreTunnel.Direct {
		return "", nil, "", "", 0, false
	}
	name, group := autoDelayStartGroup()
	if group == nil {
		return "", nil, "", "", 0, false
	}
	seen := map[string]struct{}{}
	currentName := group.Now()
	for {
		if currentName == "" || isSkipLeafProxyName(currentName) {
			return "", nil, "", "", 0, false
		}
		if _, loop := seen[currentName]; loop {
			return "", nil, "", "", 0, false
		}
		seen[currentName] = struct{}{}
		next := coreTunnel.Proxies()[currentName]
		if next == nil {
			return "", nil, "", "", 0, false
		}
		if nested, ok := next.Adapter().(outboundgroup.ProxyGroup); ok {
			name = currentName
			group = nested
			currentName = nested.Now()
			continue
		}
		url, expected := delayTestSpec(group)
		timeoutMs := startupGroupTimeout(group)
		return name, next, url, expected, timeoutMs, true
	}
}

func autoDelayStartGroup() (string, outboundgroup.ProxyGroup) {
	proxies := coreTunnel.Proxies()
	if coreTunnel.Mode() == coreTunnel.Global {
		return proxyGroupByName(proxies, "GLOBAL")
	}
	keywords := []string{"auto", "select", "proxy", "节点选择", "自动选择"}
	names := make([]string, 0, len(proxies))
	for name := range proxies {
		names = append(names, name)
	}
	sort.Strings(names)
	var fallbackName string
	var fallback outboundgroup.ProxyGroup
	for _, name := range names {
		proxy := proxies[name]
		if proxy == nil || name == "GLOBAL" || shouldSkipDelayCheckGroup(name) {
			continue
		}
		group, ok := proxy.Adapter().(outboundgroup.ProxyGroup)
		if !ok {
			continue
		}
		lower := strings.ToLower(name)
		for _, keyword := range keywords {
			if strings.Contains(lower, strings.ToLower(keyword)) {
				return name, group
			}
		}
		if fallback == nil {
			fallbackName = name
			fallback = group
		}
	}
	return fallbackName, fallback
}

func proxyGroupByName(proxies map[string]C.Proxy, name string) (string, outboundgroup.ProxyGroup) {
	proxy := proxies[name]
	if proxy == nil {
		return "", nil
	}
	group, ok := proxy.Adapter().(outboundgroup.ProxyGroup)
	if !ok {
		return "", nil
	}
	return name, group
}
