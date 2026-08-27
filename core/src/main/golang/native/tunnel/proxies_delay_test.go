package tunnel

import (
	"testing"
	"time"

	C "github.com/metacubex/mihomo/constant"
)

type delayHistoryProxy struct {
	C.Proxy
	histories map[string]C.ProxyState
}

func (p delayHistoryProxy) ExtraDelayHistories() map[string]C.ProxyState {
	return p.histories
}

func TestLatestProxyDelayUsesNewestTestURLResult(t *testing.T) {
	older := time.Unix(100, 0)
	newer := time.Unix(200, 0)
	proxy := delayHistoryProxy{histories: map[string]C.ProxyState{
		"https://old.example/204": {
			Alive:   true,
			History: []C.DelayHistory{{Time: older, Delay: 120}},
		},
		"https://current.example/204": {
			Alive:   false,
			History: []C.DelayHistory{{Time: newer, Delay: 0}},
		},
	}}

	if got := latestProxyDelay(proxy); got != 0xffff {
		t.Fatalf("latestProxyDelay() = %d; want unavailable", got)
	}
}

func TestLatestProxyDelayReturnsNewestSuccessfulResult(t *testing.T) {
	proxy := delayHistoryProxy{histories: map[string]C.ProxyState{
		"https://first.example/204": {
			Alive:   true,
			History: []C.DelayHistory{{Time: time.Unix(100, 0), Delay: 90}},
		},
		"https://second.example/204": {
			Alive:   true,
			History: []C.DelayHistory{{Time: time.Unix(200, 0), Delay: 135}},
		},
	}}

	if got := latestProxyDelay(proxy); got != 135 {
		t.Fatalf("latestProxyDelay() = %d; want 135", got)
	}
}

func TestDelayTestOperationTimeoutAllowsUnifiedDelayWarmup(t *testing.T) {
	timeout := 300 * time.Millisecond
	if got := delayTestOperationTimeout(timeout, false); got != timeout {
		t.Fatalf("delayTestOperationTimeout(normal) = %s; want %s", got, timeout)
	}
	if got := delayTestOperationTimeout(timeout, true); got != 900*time.Millisecond {
		t.Fatalf("delayTestOperationTimeout(unified) = %s; want 900ms", got)
	}
}
