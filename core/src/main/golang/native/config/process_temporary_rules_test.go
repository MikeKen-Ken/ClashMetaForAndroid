package config

import (
	"testing"

	"github.com/metacubex/mihomo/config"
	T "github.com/metacubex/mihomo/tunnel"
)

func TestPatchTemporaryRulesSkipsInvalid(t *testing.T) {
	cfg := &config.RawConfig{
		Mode: T.Rule,
		ClashForAndroid: config.RawClashForAndroid{
			TemporaryRules: []config.RawTemporaryRule{
				{RuleType: "PROCESS-NAME", Payload: "ok.app", Target: "DIRECT"},
				{RuleType: "DOMAIN-REGEX", Payload: "evil.com", Target: "DIRECT"},
				{RuleType: "PROCESS-NAME", Payload: "comma,name", Target: "DIRECT"},
				{RuleType: "PROCESS-NAME", Payload: "stale.app", Target: "MissingGroup"},
				{RuleType: "DOMAIN-SUFFIX", Payload: "example.com", Target: "Auto"},
			},
		},
		ProxyGroup: []map[string]any{{"name": "Auto"}},
		Rule:       []string{"MATCH,DIRECT"},
	}
	if err := patchTemporaryRules(cfg, ""); err != nil {
		t.Fatal(err)
	}
	if len(cfg.Rule) != 3 {
		t.Fatalf("expected 2 temp rules + MATCH, got %v", cfg.Rule)
	}
	if cfg.Rule[0] != "PROCESS-NAME,ok.app,DIRECT" {
		t.Fatalf("first rule: %q", cfg.Rule[0])
	}
	if cfg.Rule[1] != "DOMAIN-SUFFIX,example.com,Auto" {
		t.Fatalf("second rule: %q", cfg.Rule[1])
	}
}
