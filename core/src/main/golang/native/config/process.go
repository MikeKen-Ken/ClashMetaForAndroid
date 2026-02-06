package config

import (
	"encoding/json"
	"errors"
	"fmt"
	"strings"

	"github.com/dlclark/regexp2"

	"cfa/native/common"

	"github.com/metacubex/mihomo/common/utils"
	"github.com/metacubex/mihomo/config"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/log"
	T "github.com/metacubex/mihomo/tunnel"
)

var (
	directNameservers = []string{
		"https://dns.alidns.com/dns-query",
		"https://120.53.53.53/dns-query",
		"tls://119.29.29.29:853",
	}
	globalNameservers = []string{
		"https://1.1.1.1/dns-query",
		"https://8.8.8.8/dns-query",
		"https://9.9.9.9/dns-query",
		"tls://1.0.0.1:853",
	}
)

var processors = []processor{
	patchExternalController, // must before patchOverride, so we only apply ExternalController in Override settings
	patchOverride,
	patchDirectGlobalMode, // after patchOverride: force rule mode + override rules/dns when session mode is direct/global
	patchGeneral,
	patchProfile,
	patchDns,
	patchTun,
	patchListeners,
	patchProviders,
	validConfig,
}

type processor func(cfg *config.RawConfig, profileDir string) error

func patchOverride(cfg *config.RawConfig, _ string) error {
	if err := json.NewDecoder(strings.NewReader(ReadOverride(OverrideSlotPersist))).Decode(cfg); err != nil {
		log.Warnln("Apply persist override: %s", err.Error())
	}
	if err := json.NewDecoder(strings.NewReader(ReadOverride(OverrideSlotSession))).Decode(cfg); err != nil {
		log.Warnln("Apply session override: %s", err.Error())
	}

	return nil
}

// patchDirectGlobalMode: when session mode is direct/global, force mode=rule and override rules + dns
// so that traffic uses MATCH,⬆️ or MATCH,🔀 (same behavior as desktop).
func patchDirectGlobalMode(cfg *config.RawConfig, _ string) error {
	switch cfg.Mode {
	case T.Direct:
		cfg.Mode = T.Rule
		cfg.Rule = []string{"MATCH,⬆️"}
		cfg.DNS.NameServer = append([]string(nil), directNameservers...)
		cfg.DNS.NameServerPolicy = nil
		log.Infoln("Applied direct mode overrides (rules + dns)")
	case T.Global:
		cfg.Mode = T.Rule
		cfg.Rule = []string{"MATCH,🔀"}
		cfg.DNS.NameServer = append([]string(nil), globalNameservers...)
		cfg.DNS.NameServerPolicy = nil
		log.Infoln("Applied global mode overrides (rules + dns)")
	default:
		// rule or other: no override
	}
	return nil
}

func patchExternalController(cfg *config.RawConfig, _ string) error {
	cfg.ExternalController = ""
	cfg.ExternalControllerTLS = ""

	return nil
}

func patchGeneral(cfg *config.RawConfig, profileDir string) error {
	cfg.Interface = ""
	cfg.RoutingMark = 0
	if cfg.ExternalController != "" || cfg.ExternalControllerTLS != "" {
		cfg.ExternalUI = profileDir + "/ui"
	}

	return nil
}

func patchProfile(cfg *config.RawConfig, _ string) error {
	cfg.Profile.StoreSelected = false
	cfg.Profile.StoreFakeIP = true

	return nil
}

func patchDns(cfg *config.RawConfig, _ string) error {
	if !cfg.DNS.Enable {
		cfg.DNS = config.DefaultRawConfig().DNS
		cfg.DNS.Enable = true
		cfg.DNS.NameServer = defaultNameServers
		cfg.DNS.EnhancedMode = C.DNSFakeIP
		cfg.DNS.FakeIPRange = defaultFakeIPRange
		cfg.DNS.FakeIPFilter = defaultFakeIPFilter

		cfg.ClashForAndroid.AppendSystemDNS = true
	}

	if cfg.ClashForAndroid.AppendSystemDNS {
		cfg.DNS.NameServer = append(cfg.DNS.NameServer, "system://")
	}

	return nil
}

func patchTun(cfg *config.RawConfig, _ string) error {
	cfg.Tun.Enable = false
	cfg.Tun.AutoRoute = false
	cfg.Tun.AutoDetectInterface = false
	return nil
}

func patchListeners(cfg *config.RawConfig, _ string) error {
	newListeners := make([]map[string]any, 0, len(cfg.Listeners))
	for _, mapping := range cfg.Listeners {
		if proxyType, existType := mapping["type"].(string); existType {
			switch proxyType {
			case "tproxy", "redir", "tun":
				continue // remove those listeners which is not supported
			}
		}
		newListeners = append(newListeners, mapping)
	}
	cfg.Listeners = newListeners
	return nil
}

func patchProviders(cfg *config.RawConfig, profileDir string) error {
	forEachProviders(cfg, func(index int, total int, key string, provider map[string]any, prefix string) {
		path, _ := provider["path"].(string)
		if len(path) > 0 {
			path = common.ResolveAsRoot(path)
		} else if url, ok := provider["url"].(string); ok {
			path = prefix + "/" + utils.MakeHash([]byte(url)).String() // same as C.GetPathByHash
		} else {
			return // both path and url are empty, maybe inline provider
		}
		provider["path"] = profileDir + "/providers/" + path
	})

	return nil
}

func validConfig(cfg *config.RawConfig, _ string) error {
	if len(cfg.Proxy) == 0 && len(cfg.ProxyProvider) == 0 {
		return errors.New("profile does not contain `proxies` or `proxy-providers`")
	}

	if _, err := regexp2.Compile(cfg.ClashForAndroid.UiSubtitlePattern, 0); err != nil {
		return fmt.Errorf("compile ui-subtitle-pattern: %s", err.Error())
	}

	return nil
}

func process(cfg *config.RawConfig, profileDir string) error {
	for _, p := range processors {
		if err := p(cfg, profileDir); err != nil {
			return err
		}
	}

	return nil
}
