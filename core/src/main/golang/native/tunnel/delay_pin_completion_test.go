package tunnel

import (
	"context"
	"errors"
	"testing"

	"github.com/metacubex/mihomo/adapter"
	"github.com/metacubex/mihomo/adapter/outboundgroup"
	"github.com/metacubex/mihomo/common/utils"
	C "github.com/metacubex/mihomo/constant"
	P "github.com/metacubex/mihomo/constant/provider"
	coreTunnel "github.com/metacubex/mihomo/tunnel"
)

type completionLeaf struct {
	C.Proxy
	onTest func()
	fail   bool
}

func (p *completionLeaf) Name() string                { return "node" }
func (p *completionLeaf) AliveForTestUrl(string) bool { return true }
func (p *completionLeaf) URLTest(context.Context, string, utils.IntRanges[uint16]) (uint16, error) {
	if p.onTest != nil {
		p.onTest()
	}
	if p.fail {
		return 0, errors.New("probe failed")
	}
	return 50, nil
}

type completionProvider struct {
	P.ProxyProvider
	leaf C.Proxy
}

func (p completionProvider) Version() uint32    { return 1 }
func (p completionProvider) Proxies() []C.Proxy { return []C.Proxy{p.leaf} }

func TestDelayCompletionReturnsGroupsToAutomatic(t *testing.T) {
	for _, kind := range []string{"url-test", "fallback"} {
		for _, scenario := range []string{"automatic", "new-manual-pin", "failed", "replaced-profile"} {
			t.Run(kind+"/"+scenario, func(t *testing.T) {
				oldProxies, oldProviders := coreTunnel.Proxies(), coreTunnel.Providers()
				t.Cleanup(func() { coreTunnel.UpdateProxies(oldProxies, oldProviders) })
				leaf := &completionLeaf{fail: scenario == "failed"}
				option := &outboundgroup.GroupCommonOption{Name: "Auto", URL: "https://example.test/204"}
				providers := []P.ProxyProvider{completionProvider{leaf: leaf}}
				var group interface {
					C.ProxyAdapter
					ForceSet(string)
					NowIsManual() bool
				}
				if kind == "url-test" {
					group = outboundgroup.NewURLTest(option, providers)
				} else {
					group = outboundgroup.NewFallback(option, providers)
				}
				group.ForceSet("node")
				if scenario == "new-manual-pin" {
					leaf.onTest = func() { group.ForceSet("node") }
				}
				if scenario == "replaced-profile" {
					leaf.onTest = func() { coreTunnel.UpdateProxies(map[string]C.Proxy{}, nil) }
				}
				coreTunnel.UpdateProxies(map[string]C.Proxy{"Auto": adapter.NewProxy(group)}, nil)
				result := HealthCheckWithTimeout("Auto", 1000, 1)
				if (result.Succeeded == 1) != !leaf.fail {
					t.Fatalf("test did not succeed: %+v", result)
				}
				wantManual := scenario != "automatic"
				if group.NowIsManual() != wantManual {
					t.Fatalf("manual=%v, want %v", group.NowIsManual(), wantManual)
				}
			})
		}
	}
}

func TestStartupOrderDoesNotCreateManualPin(t *testing.T) {
	oldProxies, oldProviders := coreTunnel.Proxies(), coreTunnel.Providers()
	t.Cleanup(func() { coreTunnel.UpdateProxies(oldProxies, oldProviders) })
	group := outboundgroup.NewURLTest(&outboundgroup.GroupCommonOption{Name: "Auto", URL: "https://example.test/204"}, []P.ProxyProvider{completionProvider{leaf: &completionLeaf{}}})
	coreTunnel.UpdateProxies(map[string]C.Proxy{"Auto": adapter.NewProxy(group)}, nil)
	ApplyStartupAutoGroupOrder()
	if group.NowIsManual() {
		t.Fatal("startup created a manual pin")
	}
}
