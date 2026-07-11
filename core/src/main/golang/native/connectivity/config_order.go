package connectivity

import (
	"sort"
	"strings"

	"github.com/metacubex/mihomo/config"
)

func proxyNameFromMapping(item map[string]any) string {
	if item == nil {
		return ""
	}
	name, _ := item["name"].(string)
	return name
}

func sortProxyMappings(proxies []map[string]any) {
	if len(proxies) <= 1 {
		return
	}
	names := make([]string, len(proxies))
	for i, item := range proxies {
		names[i] = proxyNameFromMapping(item)
	}
	sorted := SortNamesByConnectivity(names)
	indexByName := make(map[string]int, len(sorted))
	for i, name := range sorted {
		indexByName[name] = i
	}
	sort.SliceStable(proxies, func(i, j int) bool {
		ni := proxyNameFromMapping(proxies[i])
		nj := proxyNameFromMapping(proxies[j])
		return indexByName[ni] < indexByName[nj]
	})
}

func shouldApplyConnectivityOrder(group map[string]any) bool {
	if group == nil {
		return false
	}
	raw, _ := group["type"].(string)
	switch strings.ToLower(raw) {
	case "url-test", "fallback":
		return true
	default:
		return false
	}
}

func sortGroupProxiesField(group map[string]any) {
	if !shouldApplyConnectivityOrder(group) {
		return
	}
	raw, ok := group["proxies"]
	if !ok || raw == nil {
		return
	}
	switch list := raw.(type) {
	case []any:
		names := make([]string, 0, len(list))
		for _, item := range list {
			name, ok := item.(string)
			if !ok || name == "" {
				continue
			}
			names = append(names, name)
		}
		if len(names) <= 1 {
			return
		}
		sorted := SortNamesByConnectivity(names)
		out := make([]any, len(sorted))
		for i, name := range sorted {
			out[i] = name
		}
		group["proxies"] = out
	case []string:
		if len(list) <= 1 {
			return
		}
		group["proxies"] = SortNamesByConnectivity(list)
	}
}

// ApplyProxyOrderToRawConfig 在配置加载/重载前按联通评分重排 proxies 与各组 proxies 列表（不触发额外 reload）。
// 仅 url-test / fallback 参与联通重排；select 等手动组保持配置默认顺序。
func ApplyProxyOrderToRawConfig(cfg *config.RawConfig) {
	if cfg == nil {
		return
	}
	if len(cfg.Proxy) > 1 {
		sortProxyMappings(cfg.Proxy)
	}
	for _, group := range cfg.ProxyGroup {
		if group != nil {
			sortGroupProxiesField(group)
		}
	}
}
