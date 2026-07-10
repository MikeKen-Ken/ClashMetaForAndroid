package app

import (
	"strings"

	"github.com/metacubex/mihomo/component/resolver"
	"github.com/metacubex/mihomo/dns"
	"github.com/metacubex/mihomo/tunnel"
)

func NotifyDnsChanged(dnsList string) {
	var addr []string
	if len(dnsList) > 0 {
		addr = strings.Split(dnsList, ",")
	}
	dns.UpdateSystemDNS(addr)
	// 直接调用内核 tunnel，避免 app → cfa/native/tunnel → … → delegate → app 导入环
	tunnel.CloseAllConnections()
	_ = resolver.FlushFakeIP()
	dns.FlushCacheWithDefaultResolver()
}

// FlushFakeIPCache 清空全部 Fake-IP 映射、DNS 应答缓存，并重置 DoH/DoT/DoQ 连接
func FlushFakeIPCache() error {
	return resolver.FlushFakeIP()
}
