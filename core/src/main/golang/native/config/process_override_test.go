package config

import (
	"testing"

	"github.com/metacubex/mihomo/config"
)

func TestDecodeFilteredOverrideAppliesLanPort(t *testing.T) {
	cfg := &config.RawConfig{MixedPort: 7890}
	if err := decodeFilteredOverride(`{"cfa-lan-port":41234}`, cfg); err != nil {
		t.Fatal(err)
	}
	if cfg.MixedPort != 41234 {
		t.Fatalf("mixed port = %d, want 41234", cfg.MixedPort)
	}
}

func TestDecodeFilteredOverrideKeepsLegacyMixedPortStripped(t *testing.T) {
	cfg := &config.RawConfig{MixedPort: 7890}
	if err := decodeFilteredOverride(`{"mixed-port":41234}`, cfg); err != nil {
		t.Fatal(err)
	}
	if cfg.MixedPort != 7890 {
		t.Fatalf("legacy mixed-port override applied: %d", cfg.MixedPort)
	}
}

func TestDecodeFilteredOverrideIgnoresInvalidLanPort(t *testing.T) {
	cfg := &config.RawConfig{MixedPort: 7890}
	if err := decodeFilteredOverride(`{"cfa-lan-port":0}`, cfg); err != nil {
		t.Fatal(err)
	}
	if cfg.MixedPort != 7890 {
		t.Fatalf("invalid lan port applied: %d", cfg.MixedPort)
	}
}

func TestDecodeFilteredOverrideKeepsUnifiedDelay(t *testing.T) {
	cfg := &config.RawConfig{}
	if err := decodeFilteredOverride(`{"unified-delay":true}`, cfg); err != nil {
		t.Fatal(err)
	}
	if !cfg.UnifiedDelay {
		t.Fatal("unified-delay in persist/session override must not be stripped")
	}
}
