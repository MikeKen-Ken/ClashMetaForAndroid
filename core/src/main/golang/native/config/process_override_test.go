package config

import (
	"testing"

	"github.com/metacubex/mihomo/config"
)

func TestDecodeFilteredOverrideKeepsUnifiedDelay(t *testing.T) {
	cfg := &config.RawConfig{}
	if err := decodeFilteredOverride(`{"unified-delay":true}`, cfg); err != nil {
		t.Fatal(err)
	}
	if !cfg.UnifiedDelay {
		t.Fatal("unified-delay in persist/session override must not be stripped")
	}
}
