package tunnel

import (
	"testing"

	C "github.com/metacubex/mihomo/constant"
)

type namedDelayTestProxy struct {
	C.Proxy
	name  string
	alive map[string]bool
}

func (p namedDelayTestProxy) Name() string {
	return p.name
}

func (p namedDelayTestProxy) AliveForTestUrl(url string) bool {
	return p.alive[url]
}

func TestEffectiveDelayTestMembersFiltersSpecialAndDuplicateNames(t *testing.T) {
	members := effectiveDelayTestMembers([]C.Proxy{
		namedDelayTestProxy{name: "DIRECT"},
		namedDelayTestProxy{name: "node-a"},
		namedDelayTestProxy{name: "node-a"},
		namedDelayTestProxy{name: "node-b"},
	})

	if len(members) != 2 || members[0].Name() != "node-a" || members[1].Name() != "node-b" {
		t.Fatalf("effectiveDelayTestMembers() = %v; want node-a, node-b", members)
	}
}

func TestFirstAliveProxyUsesCurrentGroupURL(t *testing.T) {
	proxies := []C.Proxy{
		namedDelayTestProxy{name: "node-a", alive: map[string]bool{"https://old.example/204": true}},
		namedDelayTestProxy{name: "node-b", alive: map[string]bool{"https://current.example/204": true}},
	}

	if got := firstAliveProxyName(proxies, "https://current.example/204"); got != "node-b" {
		t.Fatalf("firstAliveProxyName() = %q; want node-b", got)
	}
}
