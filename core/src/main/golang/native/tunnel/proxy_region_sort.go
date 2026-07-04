package tunnel

import (
	"sort"

	"cfa/native/connectivity"
)

func sortProxiesByRegionAndConnectivity(proxies []*Proxy) {
	if len(proxies) <= 1 {
		return
	}

	customOrder := connectivity.LoadCustomProxyOrder()
	names := make([]string, len(proxies))
	for i, p := range proxies {
		names[i] = p.Name
	}
	sorted := connectivity.SortNamesByRegionAndConnectivity(names, customOrder)
	indexByName := make(map[string]int, len(sorted))
	for i, name := range sorted {
		indexByName[name] = i
	}

	sort.SliceStable(proxies, func(i, j int) bool {
		return indexByName[proxies[i].Name] < indexByName[proxies[j].Name]
	})
}

// 保留供 compare 使用
func compareProxyNamesByRegionAndConnectivity(
	nameA, nameB string,
	originalIndexA, originalIndexB int,
	customOrder []string,
) int {
	if len(customOrder) == 0 {
		customOrder = connectivity.DefaultCustomProxyOrder
	}
	fallbackOrder := make(map[string]int)
	nextFallback := len(customOrder)

	flagA := connectivity.ResolveProxyFlag(nameA)
	flagB := connectivity.ResolveProxyFlag(nameB)
	groupA := resolveGroupOrderForCompare(flagA, customOrder, fallbackOrder, &nextFallback)
	groupB := resolveGroupOrderForCompare(flagB, customOrder, fallbackOrder, &nextFallback)

	if groupA != groupB {
		if groupA < groupB {
			return -1
		}
		return 1
	}

	successA := connectivity.GetSuccessCount(nameA)
	successB := connectivity.GetSuccessCount(nameB)
	if successA != successB {
		if successA > successB {
			return -1
		}
		return 1
	}

	if originalIndexA < originalIndexB {
		return -1
	}
	if originalIndexA > originalIndexB {
		return 1
	}
	return 0
}

func resolveGroupOrderForCompare(
	flag string,
	customOrder []string,
	fallbackOrder map[string]int,
	nextFallback *int,
) int {
	orderMap := make(map[string]int, len(customOrder))
	for i, item := range customOrder {
		orderMap[item] = i
	}
	if groupOrder, ok := orderMap[flag]; ok {
		return groupOrder
	}
	if cached, ok := fallbackOrder[flag]; ok {
		return cached
	}
	groupOrder := *nextFallback
	fallbackOrder[flag] = groupOrder
	*nextFallback++
	return groupOrder
}
