package config

import (
	"encoding/json"
	"os"
	P "path"
	"runtime"

	"cfa/native/app"
	"cfa/native/tunnel"

	"github.com/metacubex/mihomo/config"
	"github.com/metacubex/mihomo/hub"
	"github.com/metacubex/mihomo/log"
)

func UnmarshalAndPatch(profilePath string) (*config.RawConfig, error) {
	configPath := P.Join(profilePath, "config.yaml")

	configData, err := os.ReadFile(configPath)
	if err != nil {
		return nil, err
	}

	rawConfig, err := config.UnmarshalRawConfig(configData)
	if err != nil {
		return nil, err
	}

	if err := process(rawConfig, profilePath); err != nil {
		return nil, err
	}

	return rawConfig, nil
}

func Parse(rawConfig *config.RawConfig) (*config.Config, error) {
	cfg, err := config.ParseRawConfig(rawConfig)
	if err != nil {
		return nil, err
	}

	return cfg, nil
}

func Load(path string) error {
	rawCfg, err := UnmarshalAndPatch(path)
	if err != nil {
		log.Errorln("Load %s: %s", path, err.Error())

		return err
	}

	cfg, err := Parse(rawCfg)
	if err != nil {
		log.Errorln("Load %s: %s", path, err.Error())

		return err
	}

	// like hub.Parse()
	hub.ApplyConfig(cfg)

	applyProxySelectionsFromOverride()

	app.ApplySubtitlePattern(rawCfg.ClashForAndroid.UiSubtitlePattern)

	runtime.GC()

	return nil
}

type persistProxySelections struct {
	ProxySelections map[string]string `json:"proxy-selections"`
}

func applyProxySelectionsFromOverride() {
	raw := ReadOverride(OverrideSlotPersist)
	var o persistProxySelections
	if err := json.Unmarshal([]byte(raw), &o); err != nil || o.ProxySelections == nil {
		return
	}
	for group, name := range o.ProxySelections {
		tunnel.PatchSelector(group, name)
	}
}

func LoadDefault() {
	cfg, err := config.Parse([]byte{})
	if err != nil {
		panic(err.Error())
	}

	hub.ApplyConfig(cfg)
}
