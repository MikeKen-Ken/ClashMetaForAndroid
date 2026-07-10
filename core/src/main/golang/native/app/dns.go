package app

import (
	"strings"

	"cfa/native/tunnel"
	"github.com/metacubex/mihomo/component/resolver"
	"github.com/metacubex/mihomo/dns"
)

func NotifyDnsChanged(dnsList string) {
	var addr []string
	if len(dnsList) > 0 {
		addr = strings.Split(dnsList, ",")
	}
	dns.UpdateSystemDNS(addr)
	tunnel.CloseAllConnections()
	_ = resolver.FlushFakeIP()
	dns.FlushCacheWithDefaultResolver()
}

// FlushFakeIPCache 清空全部 Fake-IP 映射、DNS 应答缓存，并重置 DoH/DoT/DoQ 连接
func FlushFakeIPCache() error {
	return resolver.FlushFakeIP()
}
