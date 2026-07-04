package config

import (
	"encoding/json"
	"errors"
	"fmt"
	"strings"

	"github.com/dlclark/regexp2"

	"cfa/native/common"

	"github.com/metacubex/mihomo/common/orderedmap"
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
	patchDirectGlobalMode, // after patchOverride: force rule mode + override rules/dns when session mode is direct/global/offline
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

const (
	proxyAdsBlockRule   = "RULE-SET,ads,REJECT"
	proxyAdsNsPolicyKey = "rule-set:ads"
)

// 关闭「代理广告拦截」时记录被移除的规则位置，再次开启时按原顺序写回（同进程内有效）。
var savedProxyAdsRuleIdx *int

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
	if err := decodeFilteredOverride(prepareSessionOverrideForDecode(ReadOverride(OverrideSlotSession)), cfg); err != nil {
		log.Warnln("Apply session override: %s", err.Error())
	}

	return nil
}

// 核心 TunnelMode 不识别 offline/script 等扩展 UI 模式；解码前剥离 mode 字段并暂存。
var sessionExtendedUiMode string

const extendedUiModeOffline = "offline"

func isExtendedUiMode(mode string) bool {
	switch strings.ToLower(strings.TrimSpace(mode)) {
	case extendedUiModeOffline, "script":
		return true
	default:
		return false
	}
}

func prepareSessionOverrideForDecode(content string) string {
	sessionExtendedUiMode = ""
	trimmed := strings.TrimSpace(content)
	if trimmed == "" || trimmed == "{}" {
		return content
	}

	var payload map[string]any
	if err := json.Unmarshal([]byte(trimmed), &payload); err != nil {
		return content
	}

	if raw, ok := payload["mode"]; ok {
		if mode, ok := raw.(string); ok && isExtendedUiMode(mode) {
			sessionExtendedUiMode = strings.ToLower(strings.TrimSpace(mode))
			delete(payload, "mode")
		}
	}

	filtered, err := json.Marshal(payload)
	if err != nil {
		return content
	}
	return string(filtered)
}

// patchDirectGlobalMode: when session mode is direct/global/offline, force mode=rule and override rules + dns
// so that traffic uses MATCH,⬆️ / MATCH,🔀 / MATCH,REJECT (same behavior as desktop for direct/global).
func patchDirectGlobalMode(cfg *config.RawConfig, _ string) error {
	if sessionExtendedUiMode == extendedUiModeOffline {
		cfg.Mode = T.Rule
		cfg.Rule = []string{"MATCH,REJECT"}
		cfg.DNS.NameServerPolicy = nil
		log.Infoln("Applied offline mode overrides (all traffic REJECT)")
		return nil
	}

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

	// 不再注入 rule-set:ads 的 nameserver-policy；订阅或历史配置若含此项则一律剔除。
	stripAdsNameserverPolicyFromDNS(cfg)

	_, hasAdsProvider := cfg.RuleProvider["ads"]

	if enableProxyAdsBlock {
		if !hasAdsProvider {
			savedProxyAdsRuleIdx = nil
		}

		rulePresent := false
		for _, rule := range cfg.Rule {
			if strings.TrimSpace(rule) == proxyAdsBlockRule {
				rulePresent = true
				break
			}
		}
		if rulePresent {
			savedProxyAdsRuleIdx = nil
		}

		if hasAdsProvider && !rulePresent {
			insertAt := defaultProxyAdsRuleInsertIndex(cfg.Rule)
			if savedProxyAdsRuleIdx != nil {
				insertAt = *savedProxyAdsRuleIdx
				if insertAt < 0 {
					insertAt = 0
				}
				if insertAt > len(cfg.Rule) {
					insertAt = len(cfg.Rule)
				}
				savedProxyAdsRuleIdx = nil
			}
			cfg.Rule = insertStringAt(cfg.Rule, insertAt, proxyAdsBlockRule)
			log.Infoln("代理广告拦截：已按索引 %d 插入广告规则", insertAt)
		}
		return nil
	}

	for i, rule := range cfg.Rule {
		if strings.TrimSpace(rule) == proxyAdsBlockRule {
			idx := i
			savedProxyAdsRuleIdx = &idx
			break
		}
	}
	nextRules := make([]string, 0, len(cfg.Rule))
	for _, rule := range cfg.Rule {
		if strings.TrimSpace(rule) == proxyAdsBlockRule {
			continue
		}
		nextRules = append(nextRules, rule)
	}
	cfg.Rule = nextRules

	log.Infoln("代理广告拦截：已移除广告规则")
	return nil
}

func defaultProxyAdsRuleInsertIndex(rules []string) int {
	for i, rule := range rules {
		t := strings.TrimSpace(rule)
		if strings.HasPrefix(t, "RULE-SET,trackerslist,") {
			return intMin(i+1, len(rules))
		}
	}
	for i, rule := range rules {
		t := strings.TrimSpace(rule)
		if strings.HasPrefix(t, "MATCH,") {
			return i
		}
	}
	return len(rules)
}

func insertStringAt(slice []string, index int, item string) []string {
	if index < 0 {
		index = 0
	}
	if index > len(slice) {
		index = len(slice)
	}
	out := make([]string, 0, len(slice)+1)
	out = append(out, slice[:index]...)
	out = append(out, item)
	out = append(out, slice[index:]...)
	return out
}

// nameServerPolicyAdsKeyResolve 解析与 canonical 等价的实际键名（精确匹配或 Trim 后相等），兼容 YAML/覆写合并产生的键名差异。
func nameServerPolicyAdsKeyResolve(m *orderedmap.OrderedMap[string, any], canonical string) (string, bool) {
	if m == nil {
		return "", false
	}
	if _, ok := m.Get(canonical); ok {
		return canonical, true
	}
	for p := m.Oldest(); p != nil; p = p.Next() {
		if strings.TrimSpace(p.Key) == canonical {
			return p.Key, true
		}
	}
	return "", false
}

func stripAdsNameserverPolicyFromDNS(cfg *config.RawConfig) {
	stripAdsNameserverPolicyEntry(&cfg.DNS.NameServerPolicy)
	stripAdsNameserverPolicyEntry(&cfg.DNS.ProxyServerNameserverPolicy)
}

// stripAdsNameserverPolicyEntry 从 nameserver-policy（或 proxy-server-nameserver-policy）移除 rule-set:ads。
func stripAdsNameserverPolicyEntry(m **orderedmap.OrderedMap[string, any]) {
	om := *m
	if om == nil {
		return
	}
	k, ok := nameServerPolicyAdsKeyResolve(om, proxyAdsNsPolicyKey)
	if !ok {
		return
	}
	if _, deleted := om.Delete(k); deleted && om.Len() == 0 {
		*m = nil
	}
}

func intMin(a, b int) int {
	if a < b {
		return a
	}
	return b
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
