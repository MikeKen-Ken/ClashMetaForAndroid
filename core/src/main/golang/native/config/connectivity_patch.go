package config

import (
	"encoding/json"
	"strings"

	"github.com/metacubex/mihomo/listener"
)

type connectivityPatch struct {
	AllowLan      *bool   `json:"allow-lan"`
	BindAddress *string `json:"bind-address"`
}

// ApplyConnectivityPatchFromJSON 对齐 Hub PATCH /configs 的子集（allow-lan/bind-address）。
func ApplyConnectivityPatchFromJSON(raw string) bool {
	raw = strings.TrimSpace(raw)
	if raw == "" || raw == "{}" {
		return true
	}

	var p connectivityPatch
	if err := json.Unmarshal([]byte(raw), &p); err != nil {
		return false
	}

	if p.AllowLan != nil {
		listener.SetAllowLan(*p.AllowLan)
	}
	if p.BindAddress != nil && strings.TrimSpace(*p.BindAddress) != "" {
		listener.SetBindAddress(strings.TrimSpace(*p.BindAddress))
	}

	return true
}
