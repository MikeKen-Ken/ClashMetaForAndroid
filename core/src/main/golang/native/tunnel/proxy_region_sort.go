package tunnel

import (
	"sort"

	"cfa/native/connectivity"
)

func sortProxiesByConnectivity(proxies []*Proxy) {
	if len(proxies) <= 1 {
		return
	}

	names := make([]string, len(proxies))
	for i, p := range proxies {
		names[i] = p.Name
	}
	sorted := connectivity.SortNamesByConnectivity(names)
	indexByName := make(map[string]int, len(sorted))
	for i, name := range sorted {
		indexByName[name] = i
	}

	sort.SliceStable(proxies, func(i, j int) bool {
		return indexByName[proxies[i].Name] < indexByName[proxies[j].Name]
	})
}
