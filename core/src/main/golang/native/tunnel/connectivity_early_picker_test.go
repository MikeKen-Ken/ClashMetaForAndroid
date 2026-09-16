package tunnel

import (
	"testing"

	C "github.com/metacubex/mihomo/constant"
)

type namedDelayTestProxy struct {
	C.Proxy
	name string
}

func (p namedDelayTestProxy) Name() string {
	return p.name
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
