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

func TestLatestProxyDelayUsesOnlyCurrentTestURL(t *testing.T) {
	currentFailure := time.Unix(100, 0)
	unrelatedNewerSuccess := time.Unix(200, 0)
	proxy := delayHistoryProxy{histories: map[string]C.ProxyState{
		"https://old.example/204": {
			Alive:   true,
			History: []C.DelayHistory{{Time: unrelatedNewerSuccess, Delay: 120}},
		},
		"https://current.example/204": {
			Alive:   false,
			History: []C.DelayHistory{{Time: currentFailure, Delay: 0}},
		},
	}}

	if got := latestProxyDelayForURL(proxy, "https://current.example/204"); got != 0xffff {
		t.Fatalf("latestProxyDelayForURL() = %d; want unavailable", got)
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

	if got := latestProxyDelayForURL(proxy, "https://second.example/204"); got != 135 {
		t.Fatalf("latestProxyDelayForURL() = %d; want 135", got)
	}
}
