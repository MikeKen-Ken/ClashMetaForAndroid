package app

import (
	"strings"

	"github.com/metacubex/mihomo/component/resolver"
	"github.com/metacubex/mihomo/dns"
	"github.com/metacubex/mihomo/networkrecovery"
)

func NotifyNetworkChanged(dnsList string) {
	updateSystemDNS(dnsList)
	networkrecovery.Recover(networkrecovery.Request{
		Kind:   networkrecovery.KindRouteChanged,
		Reason: "Android network changed",
	})
}

// NotifyDnsChanged refreshes resolver state without interrupting established
// tunnel connections when only DNS metadata changes on the same network.
func NotifyDnsChanged(dnsList string) {
	updateSystemDNS(dnsList)
	networkrecovery.Recover(networkrecovery.Request{
		Kind:   networkrecovery.KindDNSChanged,
		Reason: "Android system DNS changed",
	})
}

func updateSystemDNS(dnsList string) {
	var addr []string
	if len(dnsList) > 0 {
		addr = strings.Split(dnsList, ",")
	}
	dns.UpdateSystemDNS(addr)
}

// FlushFakeIPCache 清空全部 Fake-IP 映射、DNS 应答缓存，并重置 DoH/DoT/DoQ 连接
func FlushFakeIPCache() error {
	return resolver.FlushFakeIP()
}
