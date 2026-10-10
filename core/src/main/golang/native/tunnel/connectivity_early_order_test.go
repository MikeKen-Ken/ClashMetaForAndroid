package tunnel

import (
	"context"
	"fmt"
	"sync"
	"testing"
	"time"

	"cfa/native/connectivity"

	"github.com/metacubex/mihomo/adapter"
	"github.com/metacubex/mihomo/adapter/outboundgroup"
	"github.com/metacubex/mihomo/common/utils"
	C "github.com/metacubex/mihomo/constant"
	P "github.com/metacubex/mihomo/constant/provider"
	coreTunnel "github.com/metacubex/mihomo/tunnel"
)

func TestDelayTestEarlyOrderAppliesWhenAHigherRankedNodePasses(t *testing.T) {
	var applied [][]string
	early := newDelayTestEarlyOrder([]string{"best", "late"}, 5000, func(names []string) {
		applied = append(applied, append([]string(nil), names...))
	})

	early.onResult("late", 50)
	early.onResult("late", 40)
	if len(applied) != 1 || applied[0][0] != "best" || applied[0][1] != "late" {
		t.Fatalf("first passing member did not write the score order: %v", applied)
	}

	early.onResult("best", 80)
	if len(applied) != 2 {
		t.Fatalf("a higher-ranked pass did not write the order again: %v", applied)
	}

	early.onResult("best", 70)
	if len(applied) != 2 {
		t.Fatalf("the same leader wrote the order again: %v", applied)
	}

	early.onResult("late", 0)
	early.onResult("DIRECT", 10)
	early.onResult("best", 5000)
	if len(applied) != 2 {
		t.Fatalf("a failed or excluded result wrote the order: %v", applied)
	}

	early.stop()
	early.best = ""
	early.passed = map[string]struct{}{}
	early.onResult("late", 50)
	if len(applied) != 2 {
		t.Fatalf("a result after stop wrote the order: %v", applied)
	}
}

type namedDelayLeaf struct {
	C.Proxy
	name string
	fn   func() (uint16, error)
}

func (p *namedDelayLeaf) Name() string                { return p.name }
func (p *namedDelayLeaf) AliveForTestUrl(string) bool { return true }
func (p *namedDelayLeaf) URLTest(context.Context, string, utils.IntRanges[uint16]) (uint16, error) {
	return p.fn()
}

type twoLeafProvider struct {
	P.ProxyProvider
	leaves []C.Proxy
}

func (p twoLeafProvider) Version() uint32    { return 1 }
func (p twoLeafProvider) Proxies() []C.Proxy { return p.leaves }

func TestDelayTestWritesScoreOrderBeforeTheSlowerNodeFinishes(t *testing.T) {
	t.Cleanup(connectivity.ClearAll)
	day := time.Now().Format("2006-01-02")
	if !connectivity.ReplaceRaw(fmt.Sprintf(`{"v":2,"data":{"best":{"days":{"%s":{"s":100,"ds":10000}}},"late":{"days":{"%s":{"s":100,"ds":90000}}}}}`, day, day)) {
		t.Fatal("could not seed pooled history")
	}
	oldProxies, oldProviders := coreTunnel.Proxies(), coreTunnel.Providers()
	t.Cleanup(func() { coreTunnel.UpdateProxies(oldProxies, oldProviders) })

	releaseBest := make(chan struct{})
	bestStarted := make(chan struct{})
	var once sync.Once
	best := &namedDelayLeaf{name: "best", fn: func() (uint16, error) {
		once.Do(func() { close(bestStarted) })
		<-releaseBest
		return 80, nil
	}}
	late := &namedDelayLeaf{name: "late", fn: func() (uint16, error) { return 50, nil }}
	group := outboundgroup.NewURLTest(
		&outboundgroup.GroupCommonOption{Name: "Auto", URL: "https://example.test/204"},
		[]P.ProxyProvider{twoLeafProvider{leaves: []C.Proxy{late, best}}},
	)
	coreTunnel.UpdateProxies(map[string]C.Proxy{"Auto": adapter.NewProxy(group)}, nil)

	done := make(chan DelayTestResult, 1)
	go func() { done <- HealthCheckWithTimeout("Auto", 5000, 2) }()
	select {
	case <-bestStarted:
	case <-time.After(3 * time.Second):
		t.Fatal("higher-ranked node did not start")
	}
	deadline := time.Now().Add(3 * time.Second)
	for {
		proxies := group.Proxies()
		if len(proxies) > 0 && proxies[0].Name() == "best" {
			break
		}
		if time.Now().After(deadline) {
			got := make([]string, len(proxies))
			for i, proxy := range proxies {
				got[i] = proxy.Name()
			}
			t.Fatalf("score order was not written while the slower node was still running: %v", got)
		}
		time.Sleep(10 * time.Millisecond)
	}
	close(releaseBest)
	select {
	case result := <-done:
		if result.Succeeded != 2 {
			t.Fatalf("succeeded=%d, result=%+v", result.Succeeded, result)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("delay test did not finish")
	}
}
