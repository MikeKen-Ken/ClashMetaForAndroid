package tunnel

import (
	"context"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/metacubex/mihomo/adapter/outboundgroup"
	"github.com/metacubex/mihomo/common/utils"
	C "github.com/metacubex/mihomo/constant"
	P "github.com/metacubex/mihomo/constant/provider"
	coreTunnel "github.com/metacubex/mihomo/tunnel"
)

func TestStartupDelayTestProbesSharedNodeOnceAndReusesIt(t *testing.T) {
	oldProxies, oldProviders := coreTunnel.Proxies(), coreTunnel.Providers()
	t.Cleanup(func() {
		StopStartupDelayTest()
		coreTunnel.UpdateProxies(oldProxies, oldProviders)
	})

	const (
		autoURL   = "https://auto.example/204"
		backupURL = "https://backup.example/204"
		passed    = uint16(80)
	)
	shared := newStartupLeaf("shared", passed)
	backupOnly := newStartupLeaf("backup-only", passed)
	directOnly := newStartupLeaf("direct-only", passed)
	selectorOnly := newStartupLeaf("selector-only", passed)

	auto := &startupTestGroup{
		name:    "Auto",
		kind:    C.Fallback,
		members: []C.Proxy{shared, directMember()},
		url:     autoURL,
		timeout: 5000,
	}
	backup := &startupTestGroup{
		name:    "Backup",
		kind:    C.URLTest,
		members: []C.Proxy{shared, backupOnly},
		url:     backupURL,
		timeout: 5000,
	}
	direct := &startupTestGroup{
		name:    "Direct",
		kind:    C.Fallback,
		members: []C.Proxy{directOnly},
		url:     "https://direct.example/204",
		timeout: 5000,
	}
	manual := &startupTestGroup{
		name:    "Manual",
		kind:    C.Selector,
		members: []C.Proxy{selectorOnly},
		url:     "https://manual.example/204",
		timeout: 5000,
	}
	// Profile order is Backup then Auto, the opposite of alphabetical order.
	global := &startupGlobalGroup{providers: []P.ProxyProvider{startupProvider{members: []C.Proxy{
		startupTestProxy{group: backup},
		startupTestProxy{group: auto},
	}}}}
	coreTunnel.UpdateProxies(map[string]C.Proxy{
		"GLOBAL": startupTestProxy{group: global},
		"Auto":   startupTestProxy{group: auto},
		"Backup": startupTestProxy{group: backup},
		"Direct": startupTestProxy{group: direct},
		"Manual": startupTestProxy{group: manual},
	}, nil)

	runStartupDelayTest(context.Background())

	if got := shared.probes.Load(); got != 1 {
		t.Fatalf("shared node probes = %d; want 1", got)
	}
	if got := shared.probedURL; got != backupURL {
		t.Fatalf("shared node was probed at %q; profile order starts at Backup", got)
	}
	if got := backupOnly.probes.Load(); got != 1 {
		t.Fatalf("backup-only probes = %d; want 1", got)
	}
	if got := directOnly.probes.Load(); got != 0 {
		t.Fatalf("Direct group probes = %d; want 0", got)
	}
	if got := selectorOnly.probes.Load(); got != 0 {
		t.Fatalf("selector probes = %d; want 0", got)
	}
	if got := latestProxyDelayForURL(shared, autoURL); got != passed {
		t.Fatalf("auto URL delay = %d; want %d", got, passed)
	}
	if got := latestProxyDelayForURL(shared, backupURL); got != passed {
		t.Fatalf("reused backup URL delay = %d; want %d", got, passed)
	}
}

type startupTestGroup struct {
	outboundgroup.ProxyGroup
	name    string
	kind    C.AdapterType
	members []C.Proxy
	url     string
	timeout int
}

func (g *startupTestGroup) Name() string { return g.name }

type startupGlobalGroup struct {
	*startupTestGroup
	providers []P.ProxyProvider
}

func (g *startupGlobalGroup) Providers() []P.ProxyProvider { return g.providers }
func (g *startupGlobalGroup) Type() C.AdapterType          { return C.Selector }
func (g *startupGlobalGroup) Name() string                 { return "GLOBAL" }

type startupProvider struct {
	P.ProxyProvider
	members []C.Proxy
}

func (p startupProvider) Proxies() []C.Proxy { return p.members }

func (g *startupTestGroup) Type() C.AdapterType             { return g.kind }
func (g *startupTestGroup) Proxies() []C.Proxy              { return g.members }
func (g *startupTestGroup) DelayTestSpec() (string, string) { return g.url, "" }
func (g *startupTestGroup) DelayTestTimeoutMs() int         { return g.timeout }
func (g *startupTestGroup) ReorderCachedProxies([]string)   {}

type startupTestProxy struct {
	C.Proxy
	group outboundgroup.ProxyGroup
}

func (p startupTestProxy) Adapter() C.ProxyAdapter { return p.group }
func (p startupTestProxy) Type() C.AdapterType     { return p.group.Type() }
func (p startupTestProxy) Name() string            { return p.group.Name() }

type startupLeaf struct {
	C.Proxy
	name      string
	delay     uint16
	probes    atomic.Int32
	probedURL string
	mu        sync.Mutex
	histories map[string]C.ProxyState
}

func newStartupLeaf(name string, delay uint16) *startupLeaf {
	return &startupLeaf{name: name, delay: delay, histories: map[string]C.ProxyState{}}
}

func (p *startupLeaf) Name() string            { return p.name }
func (p *startupLeaf) Type() C.AdapterType     { return C.Shadowsocks }
func (p *startupLeaf) Adapter() C.ProxyAdapter { return nil }
func (p *startupLeaf) ExtraDelayHistories() map[string]C.ProxyState {
	p.mu.Lock()
	defer p.mu.Unlock()
	out := make(map[string]C.ProxyState, len(p.histories))
	for url, state := range p.histories {
		out[url] = state
	}
	return out
}

func (p *startupLeaf) URLTest(_ context.Context, url string, _ utils.IntRanges[uint16]) (uint16, error) {
	p.probes.Add(1)
	p.mu.Lock()
	p.probedURL = url
	p.mu.Unlock()
	p.remember(url, p.delay)
	return p.delay, nil
}

func (p *startupLeaf) RememberPassedDelay(url string, delay uint16) {
	p.remember(url, delay)
}

func (p *startupLeaf) remember(url string, delay uint16) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.histories[url] = C.ProxyState{
		Alive:   true,
		History: []C.DelayHistory{{Time: time.Now(), Delay: delay}},
	}
}

type startupDirectMember struct {
	C.Proxy
}

func directMember() startupDirectMember { return startupDirectMember{} }

func (startupDirectMember) Name() string { return "DIRECT" }
