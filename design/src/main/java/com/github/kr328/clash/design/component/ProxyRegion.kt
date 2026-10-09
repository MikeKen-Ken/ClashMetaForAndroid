package com.github.kr328.clash.design.component

/**
 * Country filter for the proxy panel. Matches the desktop proxy page:
 * a leading region emoji wins, otherwise the first Chinese keyword hit.
 * Keyword order is significant when one keyword contains another.
 */
object ProxyRegion {
    data class Option(val flag: String, val label: String)

    private data class Rule(val flag: String, val keywords: List<String>)

    private val RULES = listOf(
        Rule("🇭🇰", listOf("香港")),
        Rule("🇲🇴", listOf("澳门")),
        Rule("🇹🇼", listOf("台湾", "台北", "高雄", "台中", "台南")),
        Rule("🇨🇳", listOf("中国", "大陆", "回国", "上海", "北京", "广州", "深圳", "成都", "杭州")),
        Rule("🇯🇵", listOf("日本", "东京", "大阪", "名古屋", "京都", "福冈", "札幌", "横滨")),
        Rule("🇰🇷", listOf("韩国", "南韩", "首尔", "釜山")),
        Rule("🇲🇳", listOf("蒙古")),
        Rule("🇸🇬", listOf("新加坡", "狮城")),
        Rule("🇮🇩", listOf("印度尼西亚", "印尼", "雅加达", "巴厘岛")),
        Rule("🇲🇾", listOf("马来西亚", "吉隆坡")),
        Rule("🇹🇭", listOf("泰国", "曼谷")),
        Rule("🇻🇳", listOf("越南", "胡志明", "河内")),
        Rule("🇵🇭", listOf("菲律宾", "马尼拉")),
        Rule("🇰🇭", listOf("柬埔寨", "金边")),
        Rule("🇱🇦", listOf("老挝")),
        Rule("🇲🇲", listOf("缅甸")),
        Rule("🇮🇳", listOf("印度", "孟买", "新德里", "班加罗尔")),
        Rule("🇵🇰", listOf("巴基斯坦")),
        Rule("🇧🇩", listOf("孟加拉")),
        Rule("🇱🇰", listOf("斯里兰卡")),
        Rule("🇰🇿", listOf("哈萨克斯坦", "哈萨克")),
        Rule("🇦🇪", listOf("阿联酋", "迪拜", "阿布扎比")),
        Rule("🇸🇦", listOf("沙特")),
        Rule("🇶🇦", listOf("卡塔尔")),
        Rule("🇮🇱", listOf("以色列")),
        Rule("🇮🇷", listOf("伊朗")),
        Rule("🇹🇷", listOf("土耳其", "伊斯坦布尔")),
        Rule("🇧🇾", listOf("白俄罗斯")),
        Rule("🇷🇺", listOf("俄罗斯", "莫斯科", "圣彼得堡")),
        Rule("🇺🇦", listOf("乌克兰")),
        Rule("🇷🇴", listOf("罗马尼亚")),
        Rule("🇩🇪", listOf("德国", "法兰克福", "柏林", "慕尼黑", "汉堡")),
        Rule("🇫🇷", listOf("法国", "巴黎", "马赛")),
        Rule("🇬🇧", listOf("英国", "伦敦", "曼彻斯特")),
        Rule("🇮🇪", listOf("爱尔兰", "都柏林")),
        Rule("🇳🇱", listOf("荷兰", "阿姆斯特丹")),
        Rule("🇧🇪", listOf("比利时", "布鲁塞尔")),
        Rule("🇱🇺", listOf("卢森堡")),
        Rule("🇨🇭", listOf("瑞士", "苏黎世", "日内瓦")),
        Rule("🇦🇹", listOf("奥地利", "维也纳")),
        Rule("🇮🇹", listOf("意大利", "罗马", "米兰")),
        Rule("🇪🇸", listOf("西班牙", "马德里", "巴塞罗那")),
        Rule("🇵🇹", listOf("葡萄牙", "里斯本")),
        Rule("🇬🇷", listOf("希腊", "雅典")),
        Rule("🇸🇪", listOf("瑞典", "斯德哥尔摩")),
        Rule("🇳🇴", listOf("挪威", "奥斯陆")),
        Rule("🇫🇮", listOf("芬兰", "赫尔辛基")),
        Rule("🇩🇰", listOf("丹麦", "哥本哈根")),
        Rule("🇮🇸", listOf("冰岛")),
        Rule("🇵🇱", listOf("波兰", "华沙")),
        Rule("🇨🇿", listOf("捷克")),
        Rule("🇸🇰", listOf("斯洛伐克")),
        Rule("🇸🇮", listOf("斯洛文尼亚")),
        Rule("🇭🇺", listOf("匈牙利", "布达佩斯")),
        Rule("🇧🇬", listOf("保加利亚")),
        Rule("🇷🇸", listOf("塞尔维亚")),
        Rule("🇭🇷", listOf("克罗地亚")),
        Rule("🇺🇸", listOf("美国", "纽约", "洛杉矶", "圣何塞", "阿什本", "华盛顿", "波士顿", "迈阿密", "西雅图", "芝加哥", "达拉斯", "休斯顿", "丹佛", "凤凰城", "圣地亚哥", "夏威夷", "硅谷")),
        Rule("🇨🇦", listOf("加拿大", "多伦多", "温哥华", "蒙特利尔")),
        Rule("🇲🇽", listOf("墨西哥")),
        Rule("🇧🇷", listOf("巴西", "圣保罗", "里约")),
        Rule("🇦🇷", listOf("阿根廷")),
        Rule("🇨🇱", listOf("智利")),
        Rule("🇨🇴", listOf("哥伦比亚")),
        Rule("🇵🇪", listOf("秘鲁")),
        Rule("🇿🇦", listOf("南非", "约翰内斯堡")),
        Rule("🇪🇬", listOf("埃及", "开罗")),
        Rule("🇳🇬", listOf("尼日利亚")),
        Rule("🇰🇪", listOf("肯尼亚")),
        Rule("🇲🇦", listOf("摩洛哥")),
        Rule("🇦🇺", listOf("澳大利亚", "澳洲", "悉尼", "墨尔本", "布里斯班", "珀斯")),
        Rule("🇳🇿", listOf("新西兰", "奥克兰")),
    )

    private val LABELS = mapOf(
        "🇭🇰" to "Hong Kong",
        "🇲🇴" to "Macao",
        "🇹🇼" to "Taiwan",
        "🇨🇳" to "China",
        "🇯🇵" to "Japan",
        "🇰🇷" to "South Korea",
        "🇲🇳" to "Mongolia",
        "🇸🇬" to "Singapore",
        "🇮🇩" to "Indonesia",
        "🇲🇾" to "Malaysia",
        "🇹🇭" to "Thailand",
        "🇻🇳" to "Vietnam",
        "🇵🇭" to "Philippines",
        "🇰🇭" to "Cambodia",
        "🇱🇦" to "Laos",
        "🇲🇲" to "Myanmar",
        "🇮🇳" to "India",
        "🇵🇰" to "Pakistan",
        "🇧🇩" to "Bangladesh",
        "🇱🇰" to "Sri Lanka",
        "🇰🇿" to "Kazakhstan",
        "🇦🇪" to "United Arab Emirates",
        "🇸🇦" to "Saudi Arabia",
        "🇶🇦" to "Qatar",
        "🇮🇱" to "Israel",
        "🇮🇷" to "Iran",
        "🇹🇷" to "Turkey",
        "🇧🇾" to "Belarus",
        "🇷🇺" to "Russia",
        "🇺🇦" to "Ukraine",
        "🇷🇴" to "Romania",
        "🇩🇪" to "Germany",
        "🇫🇷" to "France",
        "🇬🇧" to "United Kingdom",
        "🇮🇪" to "Ireland",
        "🇳🇱" to "Netherlands",
        "🇧🇪" to "Belgium",
        "🇱🇺" to "Luxembourg",
        "🇨🇭" to "Switzerland",
        "🇦🇹" to "Austria",
        "🇮🇹" to "Italy",
        "🇪🇸" to "Spain",
        "🇵🇹" to "Portugal",
        "🇬🇷" to "Greece",
        "🇸🇪" to "Sweden",
        "🇳🇴" to "Norway",
        "🇫🇮" to "Finland",
        "🇩🇰" to "Denmark",
        "🇮🇸" to "Iceland",
        "🇵🇱" to "Poland",
        "🇨🇿" to "Czechia",
        "🇸🇰" to "Slovakia",
        "🇸🇮" to "Slovenia",
        "🇭🇺" to "Hungary",
        "🇧🇬" to "Bulgaria",
        "🇷🇸" to "Serbia",
        "🇭🇷" to "Croatia",
        "🇺🇸" to "United States",
        "🇨🇦" to "Canada",
        "🇲🇽" to "Mexico",
        "🇧🇷" to "Brazil",
        "🇦🇷" to "Argentina",
        "🇨🇱" to "Chile",
        "🇨🇴" to "Colombia",
        "🇵🇪" to "Peru",
        "🇿🇦" to "South Africa",
        "🇪🇬" to "Egypt",
        "🇳🇬" to "Nigeria",
        "🇰🇪" to "Kenya",
        "🇲🇦" to "Morocco",
        "🇦🇺" to "Australia",
        "🇳🇿" to "New Zealand",
    )

    private val FLAGS = RULES.map { it.flag }

    fun label(flag: String): String? = LABELS[flag]

    /** Empty string when the name has no known country. */
    fun resolve(proxyName: String): String {
        val trimmed = proxyName.trim()
        for (flag in FLAGS) {
            if (trimmed.startsWith(flag)) return flag
        }
        for (rule in RULES) {
            if (rule.keywords.any { keyword -> trimmed.contains(keyword) }) {
                return rule.flag
            }
        }
        return ""
    }

    /** Countries present in [names], A-Z by the English name shown in the drawer. */
    fun listAvailable(names: Iterable<String>): List<Option> {
        val flags = LinkedHashSet<String>()
        for (name in names) {
            val flag = resolve(name)
            if (flag.isNotEmpty()) flags.add(flag)
        }
        return flags
            .map { flag -> Option(flag, LABELS[flag] ?: flag) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }
}
