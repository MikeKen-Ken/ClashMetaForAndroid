package tunnel

import (
	"fmt"
	"os"
	"sort"
	"testing"
	"time"

	"cfa/native/connectivity"
	"github.com/metacubex/mihomo/adapter/outboundgroup"
	C "github.com/metacubex/mihomo/constant"
	coreTunnel "github.com/metacubex/mihomo/tunnel"
)

func TestMain(m *testing.M) {
	dir, err := os.MkdirTemp("", "connectivity-tunnel-tests-")
	if err != nil {
		panic(err)
	}
	C.SetHomeDir(dir)
	code := m.Run()
	_ = os.RemoveAll(dir)
	os.Exit(code)
}

type rankingTestGroup struct {
	outboundgroup.ProxyGroup
	members []C.Proxy
	pin     string
	clears  int
}

func (g *rankingTestGroup) Type() C.AdapterType             { return C.Fallback }
func (g *rankingTestGroup) Proxies() []C.Proxy              { return g.members }
func (g *rankingTestGroup) Now() string                     { return g.pin }
func (g *rankingTestGroup) NowIsManual() bool               { return true }
func (g *rankingTestGroup) ClearManualSelection()           { g.clears++; g.pin = "" }
func (g *rankingTestGroup) DelayTestSpec() (string, string) { return "target", "" }
func (g *rankingTestGroup) ReorderCachedProxies(names []string) {
	indices := make(map[string]int)
	for i, name := range names {
		indices[name] = i
	}
	sort.SliceStable(g.members, func(i, j int) bool { return indices[g.members[i].Name()] < indices[g.members[j].Name()] })
}

type rankingTestWrapper struct {
	C.Proxy
	group *rankingTestGroup
}

func (p rankingTestWrapper) Adapter() C.ProxyAdapter { return p.group }
func (p rankingTestWrapper) Type() C.AdapterType     { return C.Fallback }

type rankingTestLeaf struct{ rankingHistoryProxy }

func (p rankingTestLeaf) Type() C.AdapterType     { return C.Shadowsocks }
func (p rankingTestLeaf) Adapter() C.ProxyAdapter { return nil }

func TestDefaultViewFollowsStableRuntimeOrderWithoutClearingPin(t *testing.T) {
	oldProxies, oldProviders := coreTunnel.Proxies(), coreTunnel.Providers()
	t.Cleanup(func() {
		coreTunnel.UpdateProxies(oldProxies, oldProviders)
		StopConnectivityMaintenance()
		connectivity.ClearAll()
	})
	StopConnectivityMaintenance()
	day := time.Now().Format("2006-01-02")
	if !connectivity.ReplaceRaw(fmt.Sprintf(`{"v":2,"data":{"slow":{"days":{"%s":{"s":100,"ds":90000}}},"fast":{"days":{"%s":{"s":100,"ds":10000}}}}}`, day, day)) {
		t.Fatal("could not seed pooled history")
	}
	stamp := time.Now().Add(-2 * time.Minute)
	leaf := func(name string) rankingTestLeaf {
		return rankingTestLeaf{rankingHistoryProxy{name: name, delayHistoryProxy: delayHistoryProxy{histories: map[string]C.ProxyState{
			"target": {Alive: true, History: []C.DelayHistory{{Time: stamp, Delay: 100}}},
		}}}}
	}
	slow, fast := leaf("slow"), leaf("fast")
	group := &rankingTestGroup{members: []C.Proxy{slow, fast}, pin: "slow"}
	coreTunnel.UpdateProxies(map[string]C.Proxy{"Auto": rankingTestWrapper{group: group}}, nil)
	ApplyRuntimeConnectivityOrder("Auto")
	for i := 0; i < 3; i++ {
		if got := QueryProxyGroup("Auto", Default, nil).Proxies[0].Name; got != "slow" {
			t.Fatalf("default view discarded hysteresis: %s", got)
		}
	}
	if got := QueryProxyGroup("Auto", Score, nil).Proxies[0].Name; got != "fast" {
		t.Fatalf("explicit score view=%s", got)
	}
	fast.histories["target"] = C.ProxyState{Alive: true, History: []C.DelayHistory{{Time: stamp.Add(time.Minute), Delay: 100}}}
	ApplyRuntimeConnectivityOrder("Auto")
	if got := QueryProxyGroup("Auto", Default, nil).Proxies[0].Name; got != "fast" {
		t.Fatalf("second measurement did not reach UI: %s", got)
	}
	if group.clears != 0 || group.pin != "slow" {
		t.Fatal("ranking changed manual selection")
	}
}
