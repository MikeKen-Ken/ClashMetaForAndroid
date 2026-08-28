package app

import (
	"strings"
	"sync"

	"github.com/metacubex/mihomo/component/resolver"
	"github.com/metacubex/mihomo/dns"
	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel"
)

var networkRecoveryMu sync.Mutex

func NotifyNetworkChanged(dnsList string) {
	var addr []string
	if len(dnsList) > 0 {
		addr = strings.Split(dnsList, ",")
	}
	dns.UpdateSystemDNS(addr)
	RecoverNetworkState("Android network changed")
}

// RecoverNetworkState closes connections tied to the old route, clears DNS
// state, and discards reusable protocol sessions. Manual delay tests must not
// call this path; it is reserved for real network transitions and escalated DNS
// recovery.
func RecoverNetworkState(reason string) {
	networkRecoveryMu.Lock()
	defer networkRecoveryMu.Unlock()

	log.Warnln("[Network] recovering network state: %s", reason)
	tunnel.CloseAllConnections()
	_ = resolver.FlushFakeIP()
	reset := tunnel.ResetNetworkState()
	log.Infoln("[Network] recovery complete; reset %d reusable protocol clients", reset)
}

// FlushFakeIPCache 清空全部 Fake-IP 映射、DNS 应答缓存，并重置 DoH/DoT/DoQ 连接
func FlushFakeIPCache() error {
	return resolver.FlushFakeIP()
}
