package tunnel

import (
	"context"
	"time"

	"github.com/metacubex/mihomo/adapter/outboundgroup"
	pvd "github.com/metacubex/mihomo/adapter/provider"
	"github.com/metacubex/mihomo/common/utils"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel"
)

// HealthCheckProxy measures one member of a group. It does not clear a manual
// pin and does not reorder the group. A successful whole-group test still does both.
func HealthCheckProxy(groupName, proxyName string, timeoutMs int) (result DelayTestResult) {
	startedAt := time.Now()
	result = DelayTestResult{Group: groupName, Tested: 1}
	defer func() {
		result.ElapsedMs = time.Since(startedAt).Milliseconds()
		result.Failed = result.Tested - result.Succeeded
	}()

	if shouldSkipDelayCheckGroup(groupName) {
		result.Tested = 0
		result.Error = "group is excluded from delay testing"
		return result
	}
	if timeoutMs <= 0 {
		result.Tested = 0
		result.Error = "timeout must be greater than zero"
		return result
	}
	if isSkipLeafProxyName(proxyName) {
		result.Tested = 0
		result.Error = "proxy is excluded from delay testing"
		return result
	}

	p := tunnel.Proxies()[groupName]
	if p == nil {
		result.Tested = 0
		result.Error = "proxy group not found"
		return result
	}
	g, ok := p.Adapter().(outboundgroup.ProxyGroup)
	if !ok {
		result.Tested = 0
		result.Error = "proxy is not a testable group"
		return result
	}

	var target C.Proxy
	for _, px := range g.Proxies() {
		if px != nil && px.Name() == proxyName {
			target = px
			break
		}
	}
	if target == nil {
		result.Tested = 0
		result.Error = "proxy not found in group"
		return result
	}

	testURL, expectedStatusText := delayTestSpec(g)
	expectedStatus, err := utils.NewUnsignedRanges[uint16](expectedStatusText)
	if err != nil {
		log.Debugln("[delay-test] group=%s proxy=%s invalid expected status %q: %v", groupName, proxyName, expectedStatusText, err)
		expectedStatus = nil
	}

	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutMs)*time.Millisecond)
	defer cancel()
	ctx = C.WithHealthCheckSourceName(ctx, groupName)
	if !pvd.AcquireHealthCheckWorker(ctx) {
		result.Error = "delay test worker unavailable"
		return result
	}
	defer pvd.ReleaseHealthCheckWorker()
	ctx = C.WithDelayTestTimeoutMs(ctx, timeoutMs)

	delay, testErr := target.URLTest(ctx, testURL, expectedStatus)
	if testErr != nil || delay == 0 || int(delay) >= timeoutMs {
		log.Debugln("[delay-test] group=%s proxy=%s url=%s timeoutMs=%d failed: %v", groupName, proxyName, testURL, timeoutMs, testErr)
		result.Error = "proxy timed out or failed"
		return result
	}
	result.Succeeded = 1
	return result
}
