package tunnel

import (
	"context"
	"sort"
	"sync"
	"sync/atomic"

	"github.com/metacubex/mihomo/adapter/outboundgroup"
	pvd "github.com/metacubex/mihomo/adapter/provider"
	"github.com/metacubex/mihomo/common/utils"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/log"
	coreTunnel "github.com/metacubex/mihomo/tunnel"
)

// Startup delay test matches the desktop proxy-page pass: every url-test and
// fallback member is tested once after the profile loads. Direct and Final are
// skipped. A node name is probed once and later groups reuse that result.
var startupDelayTest = struct {
	sync.Mutex
	cancel context.CancelFunc
	done   chan struct{}
}{}

type passedDelayRecorder interface {
	RememberPassedDelay(url string, delay uint16)
}

type startupDelayTimeout interface {
	DelayTestTimeoutMs() int
}

func StopStartupDelayTest() {
	startupDelayTest.Lock()
	defer startupDelayTest.Unlock()
	if startupDelayTest.cancel != nil {
		startupDelayTest.cancel()
		<-startupDelayTest.done
		startupDelayTest.cancel = nil
	}
}

func StartStartupDelayTest() {
	startupDelayTest.Lock()
	defer startupDelayTest.Unlock()
	if startupDelayTest.cancel != nil {
		return
	}
	ctx, cancel := context.WithCancel(context.Background())
	startupDelayTest.cancel = cancel
	startupDelayTest.done = make(chan struct{})
	done := startupDelayTest.done
	go func() {
		defer close(done)
		runStartupDelayTest(ctx)
	}()
}

func runStartupDelayTest(ctx context.Context) {
	names := startupAutoGroupNames()
	if len(names) == 0 || ctx.Err() != nil {
		return
	}
	log.Infoln("Startup delay test begin, groups: %d", len(names))
	reuse := map[string]uint16{}
	for _, name := range names {
		if ctx.Err() != nil {
			return
		}
		if !testStartupGroup(ctx, name, reuse) {
			return
		}
	}
	if ctx.Err() != nil {
		return
	}
	ApplyRuntimeConnectivityOrderAll()
	log.Infoln("Startup delay test finished, groups: %d", len(names))
}

func startupAutoGroupNames() []string {
	// Desktop lists groups that are outside GLOBAL first, then GLOBAL's own
	// order. Names outside GLOBAL have no profile order here, so they are sorted.
	profile := profileProxyGroupNames()
	if profile == nil {
		return filterStartupGroups(sortedProxyNames())
	}
	inProfile := make(map[string]struct{}, len(profile))
	for _, name := range profile {
		inProfile[name] = struct{}{}
	}
	leading := make([]string, 0)
	for _, name := range sortedProxyNames() {
		if _, ok := inProfile[name]; !ok {
			leading = append(leading, name)
		}
	}
	return filterStartupGroups(append(leading, profile...))
}

func profileProxyGroupNames() []string {
	global := coreTunnel.Proxies()["GLOBAL"]
	if global == nil {
		return nil
	}
	group, ok := global.Adapter().(outboundgroup.ProxyGroup)
	if !ok {
		return nil
	}
	providers := group.Providers()
	if len(providers) == 0 || providers[0] == nil {
		return nil
	}
	members := providers[0].Proxies()
	names := make([]string, 0, len(members))
	for _, proxy := range members {
		if proxy == nil {
			continue
		}
		if _, ok := proxy.Adapter().(outboundgroup.ProxyGroup); !ok {
			continue
		}
		names = append(names, proxy.Name())
	}
	if len(names) == 0 {
		return nil
	}
	return names
}

func sortedProxyNames() []string {
	proxies := coreTunnel.Proxies()
	names := make([]string, 0, len(proxies))
	for name := range proxies {
		names = append(names, name)
	}
	sort.Strings(names)
	return names
}

func filterStartupGroups(names []string) []string {
	result := make([]string, 0, len(names))
	seen := make(map[string]struct{}, len(names))
	for _, name := range names {
		if _, ok := seen[name]; ok {
			continue
		}
		seen[name] = struct{}{}
		proxy := coreTunnel.Proxies()[name]
		if proxy == nil || shouldSkipDelayCheckGroup(name) || !isAutoSelectAdapterType(proxy.Type()) {
			continue
		}
		result = append(result, name)
	}
	return result
}

// testStartupGroup reports false when the profile was replaced or the pass was
// cancelled. A settled delay of 0 means the node failed and must not be probed
// again for a later group.
func testStartupGroup(ctx context.Context, name string, reuse map[string]uint16) bool {
	p := coreTunnel.Proxies()[name]
	if p == nil || ctx.Err() != nil {
		return ctx.Err() == nil
	}
	g, ok := p.Adapter().(outboundgroup.ProxyGroup)
	if !ok || !shouldApplyRuntimeConnectivityOrder(g.Type()) {
		return true
	}
	groupProxy := p
	timeoutMs := startupGroupTimeout(g)
	testURL, expectedStatusText := delayTestSpec(g)
	// The startup pass only refreshes delay and order; it never touches a pin.
	expectedStatus, err := utils.NewUnsignedRanges[uint16](expectedStatusText)
	if err != nil {
		log.Debugln("[startup-delay] group=%s invalid expected status %q: %v", name, expectedStatusText, err)
		expectedStatus = nil
	}
	members := effectiveDelayTestMembers(g.Proxies())
	if len(members) == 0 {
		return true
	}
	concurrency := pvd.EffectiveHealthCheckWorkerLimit()
	concurrency = pvd.ClampHealthCheckWorkerLimit(concurrency)
	if concurrency > len(members) {
		concurrency = len(members)
	}
	orderedMembers := sortProxiesByConnectivityScore(members)
	orderedNames := make([]string, len(orderedMembers))
	for i, proxy := range orderedMembers {
		orderedNames[i] = proxy.Name()
	}
	var early *delayTestEarlyOrder
	early = newDelayTestEarlyOrder(orderedNames, timeoutMs, func(names []string) {
		if coreTunnel.Proxies()[name] != groupProxy {
			return
		}
		if reorder, ok := groupProxy.Adapter().(cachedProxyReorderAble); ok {
			reorder.ReorderCachedProxies(names)
		}
	})

	var reuseMu sync.Mutex
	var succeeded atomic.Int64
	sem := make(chan struct{}, concurrency)
	var wg sync.WaitGroup
	for _, px := range orderedMembers {
		if ctx.Err() != nil {
			break
		}
		wg.Add(1)
		sem <- struct{}{}
		go func(proxy C.Proxy) {
			defer wg.Done()
			defer func() { <-sem }()
			if ctx.Err() != nil || coreTunnel.Proxies()[name] != groupProxy {
				return
			}
			proxyName := proxy.Name()
			reuseMu.Lock()
			settledDelay, settled := reuse[proxyName]
			reuseMu.Unlock()
			if settled {
				if startupDelayPassed(settledDelay, timeoutMs) {
					recordReusedStartupDelay(proxy, testURL, settledDelay)
					early.onResult(proxyName, int(settledDelay))
					notifyStartupGroupRefresh(name)
					succeeded.Add(1)
				}
				return
			}

			if !pvd.AcquireHealthCheckWorker(ctx) {
				return
			}
			defer pvd.ReleaseHealthCheckWorker()
			probeCtx := C.WithHealthCheckSourceName(ctx, name)
			probeCtx = C.WithDelayTestTimeoutMs(probeCtx, timeoutMs)
			delay, testErr := proxy.URLTest(probeCtx, testURL, expectedStatus)
			if ctx.Err() != nil || coreTunnel.Proxies()[name] != groupProxy {
				return
			}
			passed := testErr == nil && startupDelayPassed(delay, timeoutMs)
			reuseMu.Lock()
			if passed {
				reuse[proxyName] = delay
			} else {
				reuse[proxyName] = 0
			}
			reuseMu.Unlock()
			if !passed {
				log.Debugln("[startup-delay] group=%s proxy=%s url=%s timeoutMs=%d failed: %v", name, proxyName, testURL, timeoutMs, testErr)
				return
			}
			succeeded.Add(1)
			early.onResult(proxyName, int(delay))
			notifyStartupGroupRefresh(name)
		}(px)
	}
	wg.Wait()
	early.stop()
	if ctx.Err() != nil || coreTunnel.Proxies()[name] != groupProxy {
		return false
	}
	resetGroupConnectTimes(g)
	return true
}

func startupGroupTimeout(group outboundgroup.ProxyGroup) int {
	if timed, ok := group.(startupDelayTimeout); ok {
		if timeoutMs := timed.DelayTestTimeoutMs(); timeoutMs > 0 {
			return timeoutMs
		}
	}
	return 5000
}

func startupDelayPassed(delay uint16, timeoutMs int) bool {
	return delay > 0 && int(delay) < timeoutMs
}

func recordReusedStartupDelay(proxy C.Proxy, url string, delay uint16) {
	if proxy == nil || url == "" || delay == 0 {
		return
	}
	if latestProxyDelayForURL(proxy, url) == delay {
		return
	}
	recorder, ok := proxy.(passedDelayRecorder)
	if !ok {
		return
	}
	recorder.RememberPassedDelay(url, delay)
}

func notifyStartupGroupRefresh(groupName string) {
	if groupName == "" {
		return
	}
	outboundgroup.NotifyProxyGroupRefresh(groupName)
}
