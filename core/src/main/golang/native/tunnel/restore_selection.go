package tunnel

import (
	"github.com/metacubex/mihomo/adapter/outboundgroup"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel"
)

// RestoreSelections pins every saved group in a freshly parsed (not yet applied)
// proxy set. Doing it before the config is applied means the new groups never
// carry traffic unpinned, not even for the moment between apply and restore.
func RestoreSelections(proxies map[string]C.Proxy, selections map[string]string) {
	for group, name := range selections {
		restoreSelectionIn(proxies, group, name)
	}
}

// RestoreSelection re-applies a saved pin to select / url-test / fallback groups
// of the running tunnel after a (re)load. It does not require the node to be in
// the group yet (a provider may not have loaded), so the pin takes effect as soon
// as it appears. It reports false only when the group does not exist or cannot be
// pinned; the caller must keep the saved record in that case.
func RestoreSelection(group, name string) bool {
	return restoreSelectionIn(tunnel.Proxies(), group, name)
}

func restoreSelectionIn(proxies map[string]C.Proxy, group, name string) bool {
	if name == "" {
		return false
	}
	p := proxies[group]
	if p == nil {
		return false
	}
	selectable, ok := p.Adapter().(outboundgroup.SelectAble)
	if !ok {
		return false
	}
	selectable.ForceSet(name)
	log.Infoln("Restore selection %s -> %s", group, name)
	return true
}
