package tunnel

import (
	"testing"

	"cfa/native/connectivity"
	C "github.com/metacubex/mihomo/constant"
)

func proxyViewNames(proxies []*Proxy) []string {
	names := make([]string, len(proxies))
	for i, proxy := range proxies {
		names[i] = proxy.Name
	}
	return names
}

// select 组的顺序是配置/用户顺序，也是默认节点的依据；任何排序模式（含标题
// 排序）都不能改动它，否则界面看起来就像换了选中节点。
func TestSortProxyViewsNeverReordersSelector(t *testing.T) {
	connectivity.ClearAll()
	t.Cleanup(connectivity.ClearAll)

	for _, mode := range []SortMode{Default, Title, Delay, Score} {
		proxies := []*Proxy{
			{Name: "DIRECT", Title: "DIRECT", Delay: 0},
			{Name: "Auto", Title: "Auto", Delay: 80},
			{Name: "JP", Title: "JP", Delay: 300},
		}

		sortProxyViews(proxies, mode, C.Selector)

		got := proxyViewNames(proxies)
		want := []string{"DIRECT", "Auto", "JP"}
		for i := range want {
			if got[i] != want[i] {
				t.Fatalf("mode %d reordered selector: %v, want %v", mode, got, want)
			}
		}
	}
}

func TestSortProxyViewsSortsAutoGroupByDelay(t *testing.T) {
	proxies := []*Proxy{
		{Name: "slow", Title: "slow", Delay: 900},
		{Name: "fast", Title: "fast", Delay: 30},
		{Name: "dead", Title: "dead", Delay: 0},
	}

	sortProxyViews(proxies, Delay, C.Fallback)

	got := proxyViewNames(proxies)
	want := []string{"dead", "fast", "slow"}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("fallback delay sort = %v, want %v", got, want)
		}
	}
}

func TestSortProxyViewsSortsAutoGroupByTitle(t *testing.T) {
	proxies := []*Proxy{
		{Name: "JP", Title: "JP"},
		{Name: "Auto", Title: "Auto"},
	}

	sortProxyViews(proxies, Title, C.Fallback)

	got := proxyViewNames(proxies)
	if got[0] != "Auto" || got[1] != "JP" {
		t.Fatalf("fallback title sort = %v, want alphabetical", got)
	}
}
