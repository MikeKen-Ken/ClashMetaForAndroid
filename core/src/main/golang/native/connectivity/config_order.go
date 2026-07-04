package connectivity

import (
	"encoding/json"
	"os"
	"sort"

	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/config"
)

const regionOrderFileName = "proxy-region-order.json"

type regionOrderFile struct {
	CustomOrder []string `json:"customOrder"`
}

func regionOrderFilePath() string {
	return C.Path.Resolve(regionOrderFileName)
}

func LoadCustomProxyOrder() []string {
	raw, err := os.ReadFile(regionOrderFilePath())
	if err != nil || len(raw) == 0 {
		return append([]string(nil), DefaultCustomProxyOrder...)
	}
	var file regionOrderFile
	if json.Unmarshal(raw, &file) != nil || len(file.CustomOrder) == 0 {
		return append([]string(nil), DefaultCustomProxyOrder...)
	}
	return file.CustomOrder
}

func proxyNameFromMapping(item map[string]any) string {
	if item == nil {
		return ""
	}
	name, _ := item["name"].(string)
	return name
}

func sortProxyMappings(proxies []map[string]any, customOrder []string) {
	if len(proxies) <= 1 {
		return
	}
	names := make([]string, len(proxies))
	for i, item := range proxies {
		names[i] = proxyNameFromMapping(item)
	}
	sorted := SortNamesByRegionAndConnectivity(names, customOrder)
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

func sortGroupProxiesField(group map[string]any, customOrder []string) {
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
		sorted := SortNamesByRegionAndConnectivity(names, customOrder)
		out := make([]any, len(sorted))
		for i, name := range sorted {
			out[i] = name
		}
		group["proxies"] = out
	case []string:
		if len(list) <= 1 {
			return
		}
		group["proxies"] = SortNamesByRegionAndConnectivity(list, customOrder)
	}
}

// ApplyProxyOrderToRawConfig 在配置加载/重载前按联通统计重排 proxies 与各组 proxies 列表（不触发额外 reload）。
func ApplyProxyOrderToRawConfig(cfg *config.RawConfig) {
	if cfg == nil {
		return
	}
	customOrder := LoadCustomProxyOrder()
	if len(cfg.Proxy) > 1 {
		sortProxyMappings(cfg.Proxy, customOrder)
	}
	for _, group := range cfg.ProxyGroup {
		if group != nil {
			sortGroupProxiesField(group, customOrder)
		}
	}
}
