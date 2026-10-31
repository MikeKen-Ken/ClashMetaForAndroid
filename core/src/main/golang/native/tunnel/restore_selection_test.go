package tunnel

import (
	"context"
	"testing"

	"github.com/metacubex/mihomo/adapter"
	"github.com/metacubex/mihomo/adapter/outboundgroup"
	C "github.com/metacubex/mihomo/constant"
	P "github.com/metacubex/mihomo/constant/provider"
	coreTunnel "github.com/metacubex/mihomo/tunnel"
)

type pinnableGroup interface {
	C.ProxyAdapter
	ForceSet(string)
	NowIsManual() bool
}

func newPinnableGroup(kind string, leaf *completionLeaf) pinnableGroup {
	option := &outboundgroup.GroupCommonOption{Name: "Auto", URL: "https://example.test/204"}
	providers := []P.ProxyProvider{completionProvider{leaf: leaf}}
	switch kind {
	case "url-test":
		return outboundgroup.NewURLTest(option, providers)
	case "fallback":
		return outboundgroup.NewFallback(option, providers)
	default:
		return outboundgroup.NewSelector(option, providers)
	}
}

// The saved pin is restored after a reload for every selectable group type,
// even when the node is not in the group yet (a provider may still be loading).
func TestRestoreSelectionPinsEveryGroupType(t *testing.T) {
	for _, kind := range []string{"url-test", "fallback", "selector"} {
		for _, nodeName := range []string{"node", "not-loaded-yet"} {
			t.Run(kind+"/"+nodeName, func(t *testing.T) {
				oldProxies, oldProviders := coreTunnel.Proxies(), coreTunnel.Providers()
				t.Cleanup(func() { coreTunnel.UpdateProxies(oldProxies, oldProviders) })
				group := newPinnableGroup(kind, &completionLeaf{})
				coreTunnel.UpdateProxies(map[string]C.Proxy{"Auto": adapter.NewProxy(group)}, nil)

				if !RestoreSelection("Auto", nodeName) {
					t.Fatal("restore reported failure")
				}
				if !group.NowIsManual() {
					t.Fatal("restored group is not pinned")
				}
			})
		}
	}
}

func TestRestoreSelectionReportsMissingGroup(t *testing.T) {
	oldProxies, oldProviders := coreTunnel.Proxies(), coreTunnel.Providers()
	t.Cleanup(func() { coreTunnel.UpdateProxies(oldProxies, oldProviders) })
	coreTunnel.UpdateProxies(map[string]C.Proxy{}, nil)
	if RestoreSelection("Gone", "node") {
		t.Fatal("restore succeeded for a group that does not exist")
	}
	if RestoreSelection("Gone", "") {
		t.Fatal("restore succeeded for an empty node name")
	}
}

// Neither the startup reorder nor the startup delay pass may release a pin.
func TestStartupKeepsManualPin(t *testing.T) {
	for _, kind := range []string{"url-test", "fallback"} {
		t.Run(kind, func(t *testing.T) {
			oldProxies, oldProviders := coreTunnel.Proxies(), coreTunnel.Providers()
			t.Cleanup(func() { coreTunnel.UpdateProxies(oldProxies, oldProviders) })
			group := newPinnableGroup(kind, &completionLeaf{})
			coreTunnel.UpdateProxies(map[string]C.Proxy{"Auto": adapter.NewProxy(group)}, nil)
			RestoreSelection("Auto", "node")

			ApplyStartupAutoGroupOrder()
			if !group.NowIsManual() {
				t.Fatal("startup order released the pin")
			}
			if !testStartupGroup(context.Background(), "Auto", map[string]uint16{}) {
				t.Fatal("startup pass did not finish")
			}
			if !group.NowIsManual() {
				t.Fatal("startup delay pass released the pin")
			}
		})
	}
}
