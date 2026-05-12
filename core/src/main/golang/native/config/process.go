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
	"github.com/metacubex/mihomo/adapter/provider"
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
	patchProxyAdsBlock,
	patchProxyGroupTimeout,
	patchGeneral,
	patchProfile,
	patchDns,
	patchTun,
	patchListeners,
	patchProviders,
	validConfig,
}

type processor func(cfg *config.RawConfig, profileDir string) error

const proxyAdsBlockRule = "RULE-SET,ads,REJECT"

type overrideProxyAdsBlock struct {
	ProxyAdsBlock *bool `json:"proxy-ads-block"`
}

var legacyHiddenOverrideKeys = map[string]struct{}{
	// 旧“覆写设置”页面已隐藏的监听端口相关项，避免历史 override 继续污染运行配置。
	"port":       {},
	"socks-port": {},
	"redir-port": {},
	"tproxy-port": {},
	"mixed-port": {},

	// 旧页面已隐藏的通用网络覆写项。
	"authentication":           {},
	"ipv6":                     {},
	"external-controller":      {},
	"external-controller-tls":  {},
	"external-controller-cors": {},
	"secret":                   {},
	"hosts":                    {},

	// 旧页面已隐藏的 DNS 覆写项。
	"dns": {},

	// 旧页面已隐藏的 Meta Features 覆写项。
	"unified-delay":    {},
	"geodata-mode":     {},
	"tcp-concurrent":   {},
	"find-process-mode": {},
	"sniffer":          {},
	"geox-url":         {},
}

func decodeFilteredOverride(content string, cfg *config.RawConfig) error {
	trimmed := strings.TrimSpace(content)
	if trimmed == "" || trimmed == "{}" {
		return nil
	}

	var payload map[string]any
	if err := json.Unmarshal([]byte(trimmed), &payload); err != nil {
		return err
	}

	for key := range legacyHiddenOverrideKeys {
		delete(payload, key)
	}

	filtered, err := json.Marshal(payload)
	if err != nil {
		return err
	}

	return json.NewDecoder(strings.NewReader(string(filtered))).Decode(cfg)
}

func patchOverride(cfg *config.RawConfig, _ string) error {
	if err := decodeFilteredOverride(ReadOverride(OverrideSlotPersist), cfg); err != nil {
		log.Warnln("Apply persist override: %s", err.Error())
	}
	if err := decodeFilteredOverride(ReadOverride(OverrideSlotSession), cfg); err != nil {
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

func patchProxyAdsBlock(cfg *config.RawConfig, _ string) error {
	enableProxyAdsBlock := true

	var persist overrideProxyAdsBlock
	if err := json.Unmarshal([]byte(ReadOverride(OverrideSlotPersist)), &persist); err == nil && persist.ProxyAdsBlock != nil {
		enableProxyAdsBlock = *persist.ProxyAdsBlock
	}

	var session overrideProxyAdsBlock
	if err := json.Unmarshal([]byte(ReadOverride(OverrideSlotSession)), &session); err == nil && session.ProxyAdsBlock != nil {
		enableProxyAdsBlock = *session.ProxyAdsBlock
	}

	if enableProxyAdsBlock {
		if _, hasAdsProvider := cfg.RuleProvider["ads"]; hasAdsProvider {
			exists := false
			for _, rule := range cfg.Rule {
				if strings.TrimSpace(rule) == proxyAdsBlockRule {
					exists = true
					break
				}
			}
			if !exists {
				cfg.Rule = append([]string{proxyAdsBlockRule}, cfg.Rule...)
				log.Infoln("Applied proxy-ads-block override: ads rule added")
			}
		}
		return nil
	}

	nextRules := make([]string, 0, len(cfg.Rule))
	for _, rule := range cfg.Rule {
		if strings.TrimSpace(rule) == proxyAdsBlockRule {
			continue
		}
		nextRules = append(nextRules, rule)
	}
	cfg.Rule = nextRules
	log.Infoln("Applied proxy-ads-block override: ads rule removed")

	return nil
}

type overrideProxyDelayTest struct {
	ProxyDelayTestTimeoutMs   *int `json:"proxy-delay-test-timeout-ms"`
	ProxyDelayTestConcurrency *int `json:"proxy-delay-test-concurrency"`
}

func patchProxyGroupTimeout(cfg *config.RawConfig, _ string) error {
	var session overrideProxyDelayTest
	if err := json.Unmarshal([]byte(ReadOverride(OverrideSlotSession)), &session); err != nil {
		provider.SetHealthCheckWorkerLimit(30)
		return nil
	}
	if session.ProxyDelayTestTimeoutMs != nil && *session.ProxyDelayTestTimeoutMs > 0 {
		timeout := *session.ProxyDelayTestTimeoutMs
		for _, group := range cfg.ProxyGroup {
			group["timeout"] = timeout
		}
		log.Infoln("Applied proxy-delay-test-timeout override: %dms", timeout)
	}
	limit := 30
	if session.ProxyDelayTestConcurrency != nil && *session.ProxyDelayTestConcurrency > 0 {
		limit = *session.ProxyDelayTestConcurrency
	}
	provider.SetHealthCheckWorkerLimit(limit)
	log.Infoln("Applied proxy-delay-test-concurrency: %d", limit)
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
