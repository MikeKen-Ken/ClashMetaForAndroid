package app

import (
	"strings"

	"github.com/metacubex/mihomo/component/resolver"
	"github.com/metacubex/mihomo/dns"
)

func NotifyDnsChanged(dnsList string) {
	var addr []string
	if len(dnsList) > 0 {
		addr = strings.Split(dnsList, ",")
	}
	dns.UpdateSystemDNS(addr)
	dns.FlushCacheWithDefaultResolver()
}

// FlushFakeIPCache 清空 fake-ip 映射表，用于规则变更后手动修正路由
func FlushFakeIPCache() error {
	// 同时清掉 DNS resolver 响应缓存，否则 SERVFAIL/失败结果可能在 5 分钟内继续生效
	resolver.ClearCache()
	resolver.ClearDNSTrace()
	return resolver.FlushFakeIP()
}
