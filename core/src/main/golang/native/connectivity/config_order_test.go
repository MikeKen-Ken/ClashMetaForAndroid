package connectivity

import (
	"testing"

	"github.com/metacubex/mihomo/config"
)

func TestApplyManualProxyOrderKeepsSelectorOrder(t *testing.T) {
	for _, groupType := range []string{"select", "Selector", "selector"} {
		t.Run(groupType, func(t *testing.T) {
			direct := map[string]any{
				"type":    groupType,
				"proxies": []any{"DIRECT", "Auto", "JP"},
			}
			cfg := &config.RawConfig{ProxyGroup: []map[string]any{direct}}

			ApplyManualProxyOrderToRawConfig(cfg)

			list := direct["proxies"].([]any)
			if len(list) != 3 || list[0] != "DIRECT" || list[1] != "Auto" || list[2] != "JP" {
				t.Fatalf("%s proxies reordered: %v", groupType, list)
			}
		})
	}
}

func TestApplyManualProxyOrderKeepsStringSelectorOrder(t *testing.T) {
	direct := map[string]any{
		"type":    "select",
		"proxies": []string{"DIRECT", "Auto", "JP"},
	}
	cfg := &config.RawConfig{ProxyGroup: []map[string]any{direct}}

	ApplyManualProxyOrderToRawConfig(cfg)

	list := direct["proxies"].([]string)
	if len(list) != 3 || list[0] != "DIRECT" || list[1] != "Auto" || list[2] != "JP" {
		t.Fatalf("select proxies reordered: %v", list)
	}
}
