package tunnel

import (
	"sort"
	"strings"

	"cfa/native/connectivity"
)

var defaultCustomProxyOrder = []string{"🇭🇰", "🇯🇵", "🇸🇬", "🇹🇼", "🇺🇸"}

type flagKeywordRule struct {
	flag     string
	keywords []string
}

var countryFlagKeywords = []flagKeywordRule{
	{flag: "🇭🇰", keywords: []string{"香港"}},
	{flag: "🇲🇴", keywords: []string{"澳门"}},
	{flag: "🇹🇼", keywords: []string{"台湾", "台北", "高雄", "台中", "台南"}},
	{flag: "🇨🇳", keywords: []string{"中国", "大陆", "回国", "上海", "北京", "广州", "深圳", "成都", "杭州"}},
	{flag: "🇯🇵", keywords: []string{"日本", "东京", "大阪", "名古屋", "京都", "福冈", "札幌", "横滨"}},
	{flag: "🇰🇷", keywords: []string{"韩国", "南韩", "首尔", "釜山"}},
	{flag: "🇲🇳", keywords: []string{"蒙古"}},
	{flag: "🇸🇬", keywords: []string{"新加坡", "狮城"}},
	{flag: "🇮🇩", keywords: []string{"印度尼西亚", "印尼", "雅加达", "巴厘岛"}},
	{flag: "🇲🇾", keywords: []string{"马来西亚", "吉隆坡"}},
	{flag: "🇹🇭", keywords: []string{"泰国", "曼谷"}},
	{flag: "🇻🇳", keywords: []string{"越南", "胡志明", "河内"}},
	{flag: "🇵🇭", keywords: []string{"菲律宾", "马尼拉"}},
	{flag: "🇰🇭", keywords: []string{"柬埔寨", "金边"}},
	{flag: "🇱🇦", keywords: []string{"老挝"}},
	{flag: "🇲🇲", keywords: []string{"缅甸"}},
	{flag: "🇮🇳", keywords: []string{"印度", "孟买", "新德里", "班加罗尔"}},
	{flag: "🇵🇰", keywords: []string{"巴基斯坦"}},
	{flag: "🇧🇩", keywords: []string{"孟加拉"}},
	{flag: "🇱🇰", keywords: []string{"斯里兰卡"}},
	{flag: "🇰🇿", keywords: []string{"哈萨克斯坦", "哈萨克"}},
	{flag: "🇦🇪", keywords: []string{"阿联酋", "迪拜", "阿布扎比"}},
	{flag: "🇸🇦", keywords: []string{"沙特"}},
	{flag: "🇶🇦", keywords: []string{"卡塔尔"}},
	{flag: "🇮🇱", keywords: []string{"以色列"}},
	{flag: "🇮🇷", keywords: []string{"伊朗"}},
	{flag: "🇹🇷", keywords: []string{"土耳其", "伊斯坦布尔"}},
	{flag: "🇧🇾", keywords: []string{"白俄罗斯"}},
	{flag: "🇷🇺", keywords: []string{"俄罗斯", "莫斯科", "圣彼得堡"}},
	{flag: "🇺🇦", keywords: []string{"乌克兰"}},
	{flag: "🇷🇴", keywords: []string{"罗马尼亚"}},
	{flag: "🇩🇪", keywords: []string{"德国", "法兰克福", "柏林", "慕尼黑", "汉堡"}},
	{flag: "🇫🇷", keywords: []string{"法国", "巴黎", "马赛"}},
	{flag: "🇬🇧", keywords: []string{"英国", "伦敦", "曼彻斯特"}},
	{flag: "🇮🇪", keywords: []string{"爱尔兰", "都柏林"}},
	{flag: "🇳🇱", keywords: []string{"荷兰", "阿姆斯特丹"}},
	{flag: "🇧🇪", keywords: []string{"比利时", "布鲁塞尔"}},
	{flag: "🇱🇺", keywords: []string{"卢森堡"}},
	{flag: "🇨🇭", keywords: []string{"瑞士", "苏黎世", "日内瓦"}},
	{flag: "🇦🇹", keywords: []string{"奥地利", "维也纳"}},
	{flag: "🇮🇹", keywords: []string{"意大利", "罗马", "米兰"}},
	{flag: "🇪🇸", keywords: []string{"西班牙", "马德里", "巴塞罗那"}},
	{flag: "🇵🇹", keywords: []string{"葡萄牙", "里斯本"}},
	{flag: "🇬🇷", keywords: []string{"希腊", "雅典"}},
	{flag: "🇸🇪", keywords: []string{"瑞典", "斯德哥尔摩"}},
	{flag: "🇳🇴", keywords: []string{"挪威", "奥斯陆"}},
	{flag: "🇫🇮", keywords: []string{"芬兰", "赫尔辛基"}},
	{flag: "🇩🇰", keywords: []string{"丹麦", "哥本哈根"}},
	{flag: "🇮🇸", keywords: []string{"冰岛"}},
	{flag: "🇵🇱", keywords: []string{"波兰", "华沙"}},
	{flag: "🇨🇿", keywords: []string{"捷克"}},
	{flag: "🇸🇰", keywords: []string{"斯洛伐克"}},
	{flag: "🇸🇮", keywords: []string{"斯洛文尼亚"}},
	{flag: "🇭🇺", keywords: []string{"匈牙利", "布达佩斯"}},
	{flag: "🇧🇬", keywords: []string{"保加利亚"}},
	{flag: "🇷🇸", keywords: []string{"塞尔维亚"}},
	{flag: "🇭🇷", keywords: []string{"克罗地亚"}},
	{flag: "🇺🇸", keywords: []string{"美国", "纽约", "洛杉矶", "圣何塞", "阿什本", "华盛顿", "波士顿", "迈阿密", "西雅图", "芝加哥", "达拉斯", "休斯顿", "丹佛", "凤凰城", "圣地亚哥", "夏威夷", "硅谷"}},
	{flag: "🇨🇦", keywords: []string{"加拿大", "多伦多", "温哥华", "蒙特利尔"}},
	{flag: "🇲🇽", keywords: []string{"墨西哥"}},
	{flag: "🇧🇷", keywords: []string{"巴西", "圣保罗", "里约"}},
	{flag: "🇦🇷", keywords: []string{"阿根廷"}},
	{flag: "🇨🇱", keywords: []string{"智利"}},
	{flag: "🇨🇴", keywords: []string{"哥伦比亚"}},
	{flag: "🇵🇪", keywords: []string{"秘鲁"}},
	{flag: "🇿🇦", keywords: []string{"南非", "约翰内斯堡"}},
	{flag: "🇪🇬", keywords: []string{"埃及", "开罗"}},
	{flag: "🇳🇬", keywords: []string{"尼日利亚"}},
	{flag: "🇰🇪", keywords: []string{"肯尼亚"}},
	{flag: "🇲🇦", keywords: []string{"摩洛哥"}},
	{flag: "🇦🇺", keywords: []string{"澳大利亚", "澳洲", "悉尼", "墨尔本", "布里斯班", "珀斯"}},
	{flag: "🇳🇿", keywords: []string{"新西兰", "奥克兰"}},
}

var allRegionFlags []string

func init() {
	allRegionFlags = make([]string, 0, len(countryFlagKeywords))
	for _, rule := range countryFlagKeywords {
		allRegionFlags = append(allRegionFlags, rule.flag)
	}
}

func resolveProxyFlagByKeyword(proxyName string) string {
	for _, rule := range countryFlagKeywords {
		for _, keyword := range rule.keywords {
			if strings.Contains(proxyName, keyword) {
				return rule.flag
			}
		}
	}
	return ""
}

func resolveRegionFlag(proxyName string) string {
	trimmed := strings.TrimSpace(proxyName)
	for _, flag := range allRegionFlags {
		if strings.HasPrefix(trimmed, flag) {
			return flag
		}
	}
	return resolveProxyFlagByKeyword(trimmed)
}

func resolveSubscriptionGroup(proxyName string) string {
	rest := strings.TrimSpace(proxyName)
	for _, flag := range allRegionFlags {
		if strings.HasPrefix(rest, flag) {
			rest = strings.TrimSpace(strings.TrimPrefix(rest, flag))
			break
		}
	}
	if rest == "" {
		return ""
	}
	parts := strings.Fields(rest)
	if len(parts) == 0 {
		return ""
	}
	return parts[0]
}

type proxySortContext struct {
	customOrder         []string
	subscriptionOrder   map[string]int
	regionFallbackOrder map[string]int
}

func buildProxySortContext(names []string, customOrder []string) proxySortContext {
	ctx := proxySortContext{
		customOrder:         customOrder,
		subscriptionOrder:   make(map[string]int),
		regionFallbackOrder: make(map[string]int),
	}
	customSet := make(map[string]struct{}, len(customOrder))
	for _, flag := range customOrder {
		customSet[flag] = struct{}{}
	}

	nextSubscription := 0
	nextRegionFallback := len(customOrder)
	for _, name := range names {
		subscription := resolveSubscriptionGroup(name)
		if _, ok := ctx.subscriptionOrder[subscription]; !ok {
			ctx.subscriptionOrder[subscription] = nextSubscription
			nextSubscription++
		}

		flag := resolveRegionFlag(name)
		if flag == "" {
			continue
		}
		if _, inCustom := customSet[flag]; inCustom {
			continue
		}
		if _, ok := ctx.regionFallbackOrder[flag]; ok {
			continue
		}
		ctx.regionFallbackOrder[flag] = nextRegionFallback
		nextRegionFallback++
	}
	return ctx
}

func regionOrder(flag string, ctx proxySortContext) int {
	for i, item := range ctx.customOrder {
		if item == flag {
			return i
		}
	}
	if order, ok := ctx.regionFallbackOrder[flag]; ok {
		return order
	}
	return len(ctx.customOrder) + 9999
}

func compareProxySortKeys(nameA string, indexA int, nameB string, indexB int, ctx proxySortContext) bool {
	subA := ctx.subscriptionOrder[resolveSubscriptionGroup(nameA)]
	subB := ctx.subscriptionOrder[resolveSubscriptionGroup(nameB)]
	if subA != subB {
		return subA < subB
	}

	regionA := regionOrder(resolveRegionFlag(nameA), ctx)
	regionB := regionOrder(resolveRegionFlag(nameB), ctx)
	if regionA != regionB {
		return regionA < regionB
	}

	successA := connectivity.GetSuccessCount(nameA)
	successB := connectivity.GetSuccessCount(nameB)
	if successA != successB {
		return successA > successB
	}

	return indexA < indexB
}

type proxySortKey struct {
	index int
}

func sortProxiesByRegionAndConnectivity(proxies []*Proxy) {
	if len(proxies) <= 1 {
		return
	}

	names := make([]string, len(proxies))
	for i, p := range proxies {
		names[i] = p.Name
	}
	ctx := buildProxySortContext(names, defaultCustomProxyOrder)

	keys := make([]proxySortKey, len(proxies))
	for i := range proxies {
		keys[i] = proxySortKey{index: i}
	}

	sort.SliceStable(keys, func(i, j int) bool {
		a := keys[i]
		b := keys[j]
		return compareProxySortKeys(proxies[a.index].Name, a.index, proxies[b.index].Name, b.index, ctx)
	})

	sorted := make([]*Proxy, len(proxies))
	for i, key := range keys {
		sorted[i] = proxies[key.index]
	}
	copy(proxies, sorted)
}
