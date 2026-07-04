package tunnel

import (
	"sort"
	"strings"

	"cfa/native/connectivity"
)

// 默认节点地区顺序（与桌面端 profiles.customProxyOrder 一致）
var defaultCustomProxyOrder = []string{"🇭🇰", "🇯🇵", "🇸🇬", "🇹🇼", "🇺🇸"}

type flagKeywordRule struct {
	flag     string
	keywords []string
}

// 顺序敏感：长关键字必须在短关键字之前（如「印度尼西亚」在「印度」前）
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

func resolveProxyFlag(proxyName string) string {
	for _, rule := range countryFlagKeywords {
		for _, keyword := range rule.keywords {
			if strings.Contains(proxyName, keyword) {
				return rule.flag
			}
		}
	}
	return ""
}

func resolveGroupOrder(
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

type proxySortKey struct {
	index        int
	groupOrder   int
	successCount int
}

func sortProxiesByRegionAndConnectivity(proxies []*Proxy) {
	if len(proxies) <= 1 {
		return
	}

	customOrder := defaultCustomProxyOrder
	fallbackOrder := make(map[string]int)
	nextFallback := len(customOrder)

	keys := make([]proxySortKey, len(proxies))
	for i, p := range proxies {
		flag := resolveProxyFlag(p.Name)
		keys[i] = proxySortKey{
			index:        i,
			groupOrder:   resolveGroupOrder(flag, customOrder, fallbackOrder, &nextFallback),
			successCount: connectivity.GetSuccessCount(p.Name),
		}
	}

	sort.SliceStable(keys, func(i, j int) bool {
		a, b := keys[i], keys[j]
		if a.groupOrder != b.groupOrder {
			return a.groupOrder < b.groupOrder
		}
		if a.successCount != b.successCount {
			return a.successCount > b.successCount
		}
		return a.index < b.index
	})

	sorted := make([]*Proxy, len(proxies))
	for i, key := range keys {
		sorted[i] = proxies[key.index]
	}
	copy(proxies, sorted)
}
