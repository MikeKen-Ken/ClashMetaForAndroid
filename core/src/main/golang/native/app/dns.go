package app

import (
	"strings"

	"github.com/metacubex/mihomo/component/resolver"
	"github.com/metacubex/mihomo/dns"
	"github.com/metacubex/mihomo/log"
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
	err := resolver.FlushFakeIP()
	if err != nil {
		log.Warnln("[FakeIPFlush] resolver.FlushFakeIP: %v", err)
	} else {
		log.Infoln("[FakeIPFlush] resolver.FlushFakeIP completed OK")
	}
	return err
}
