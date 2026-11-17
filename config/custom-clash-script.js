function main(config) {
    const AUTO = "Auto";
    const DIRECT = "Direct";
    const FALLBACK = "Final";
    /** 仅含日本节点：Google 系 + AI / Unity / DeepSeek 统一走这里 */
    const JP = "JP";
    /** 订阅节点名称中的日本标记：JP 组只收录日本节点 */
    const JP_MARK = "🇯🇵";

    const DEFAULT_AUTO = "🚀 节点选择";
    const DEFAULT_DIRECT = "🎯 全球直连";
    const DEFAULT_FALLBACK = "🐟 漏网之鱼";

    /** 占位：将「未在 PROXY_GROUP_ORDER 中单独列出的组」按当前数组中的相对顺序插入到此处（一般为订阅里其余策略组） */
    const PROXY_GROUP_ORDER_REST = "__REST__";

    /**
     * 最终 proxy-groups 在客户端中的显示顺序。
     * 除 PROXY_GROUP_ORDER_REST 外每一项为 group.name，须与上方 AUTO / JP 等常量一致。
     * DIRECT / FALLBACK 来自订阅里「全球直连」「漏网之鱼」经脚本改名后的 name，不写进列表时等同「未点名」，会走 __REST__ 或末尾兜底。
     */
    const PROXY_GROUP_ORDER = [
        AUTO,
        JP,
        DIRECT,
        FALLBACK,
        PROXY_GROUP_ORDER_REST,
    ];

    /**
     * ========== 本地测试规则（改这里即可，无需动下方流水线） ==========
     * 生成后会插入到 rules 最前面，优先于订阅规则集匹配。
     *
     * 字段说明：
     * - type：Mihomo 规则类型，如 DOMAIN / DOMAIN-SUFFIX / DOMAIN-KEYWORD / PROCESS-NAME / IP-CIDR 等
     * - pattern：匹配内容（域名、进程名、IP 等）；type 为 MATCH 时可省略
     * - target：策略组，可用上方常量 AUTO / JP / DIRECT / FALLBACK / REJECT，或任意已有组名
     * - noResolve：可选，IP 类规则设为 true 时追加 ,no-resolve
     *
     * 进程规则需客户端开启 find-process-mode（建议 strict），否则 PROCESS-NAME 不生效。
     *
     * 示例：
     * { type: "DOMAIN-SUFFIX", pattern: "test.example.com", target: AUTO },
     * { type: "DOMAIN-KEYWORD", pattern: "debug", target: AUTO },
     * { type: "PROCESS-NAME", pattern: "chrome.exe", target: JP },
     * { type: "PROCESS-NAME", pattern: "WeChat.exe", target: DIRECT },
     * { type: "MATCH", target: "REJECT" }, // reject all
     */
    const LOCAL_TEST_RULES = [
        // { type: "MATCH", target: "REJECT" }, // reject all
        // { type: "DOMAIN-SUFFIX", pattern: "example.com", target: AUTO },
        // { type: "PROCESS-NAME", pattern: "YourApp.exe", target: AUTO },
        { type: "IP-CIDR", pattern: "130.94.32.254/32", target: DIRECT, noResolve: true },
        { type: "IP-CIDR", pattern: "130.94.45.104/32", target: DIRECT, noResolve: true },
        { type: "IP-CIDR", pattern: "23.95.79.141/32", target: DIRECT, noResolve: true },
        { type: "IP-CIDR", pattern: "23.95.79.153/32", target: DIRECT, noResolve: true },
    ];

    /** @param {{ type: string; pattern?: string; target: string; noResolve?: boolean }} rule */
    const formatLocalTestRule = (rule) => {
        if (!rule || !rule.type || !rule.target || (rule.type !== "MATCH" && !rule.pattern)) {
            console.warn("LOCAL_TEST_RULES: skip invalid entry", rule);
            return null;
        }
        if (rule.type === "MATCH") {
            return `MATCH,${rule.target}`;
        }
        const suffix = rule.noResolve ? ",no-resolve" : "";
        return `${rule.type},${rule.pattern},${rule.target}${suffix}`;
    };

    /**
     * @param {Array<Record<string, unknown>>} groups
     * @param {string[]} template
     */
    const reorderProxyGroupsByTemplate = (groups, template) => {
        const explicit = new Set(template.filter((t) => t !== PROXY_GROUP_ORDER_REST));
        const used = new Set();
        const out = [];

        const appendRest = () => {
            for (const g of groups) {
                if (!g || typeof g.name !== "string") {
                    continue;
                }
                if (used.has(g.name) || explicit.has(g.name)) {
                    continue;
                }
                used.add(g.name);
                out.push(g);
            }
        };

        for (const token of template) {
            if (token === PROXY_GROUP_ORDER_REST) {
                appendRest();
                continue;
            }
            const g = groups.find((x) => x && x.name === token);
            if (g && !used.has(g.name)) {
                used.add(g.name);
                out.push(g);
            }
        }

        for (const g of groups) {
            if (!g || typeof g.name !== "string") {
                continue;
            }
            if (!used.has(g.name)) {
                used.add(g.name);
                out.push(g);
            }
        }

        return out;
    };

    const modifyProxyGroup = (group) => {
        const groupModifications = {
            "🚀 节点选择": {
                name: AUTO,
                type: "fallback",
                url: "https://www.gstatic.com/generate_204",
                interval: 7200,
                "max-failed-times": 1,
                "max-connect-times": 15,
                "expected-status": 204,
                lazy: false,
                "disable-udp": false,
            },
        };
        if (groupModifications[group.name]) {
            return { ...group, ...groupModifications[group.name] };
        }
        if (group.proxies) {
            group.proxies = group.proxies.map((proxy) => proxy.replaceAll(DEFAULT_AUTO, AUTO));
        }
        return group;
    };

    try {
        if (!config) {
            console.error("Config is null or undefined");
            return null;
        }
        if (!config["proxy-groups"] || config["proxy-groups"].length < 3) {
            console.error("Config needs at least 3 proxy groups");
            return config;
        }

        // PROCESS-NAME 规则依赖进程查找；off 时规则集不会生效
        config["proxy-groups"] = config["proxy-groups"].map(modifyProxyGroup);

        if (config["proxy-groups"].length > 0) {
            const removeLastChar = (name) => {
                if (name.length > 1 && /^[A-Z]$/i.test(name.slice(-1)) && name.slice(-2, -1) !== "-") {
                    return name.slice(0, -1);
                }
                return name;
            };

            /** 日本节点：国旗优先，兼容「日本 / Japan / JP」命名；在裁剪尾字母前判定，避免 JP/Japan 被误裁 */
            const isJapanNode = (proxy) =>
                typeof proxy === "string" &&
                (proxy.includes(JP_MARK) || /日本|Japan|(?:^|[^A-Za-z])JP(?:$|[^A-Za-z])/i.test(proxy));
            const rawFirstGroupProxies = Array.isArray(config["proxy-groups"][0].proxies)
                ? config["proxy-groups"][0].proxies
                : [];
            const japanIndexes = rawFirstGroupProxies
                .map((proxy, index) => (isJapanNode(proxy) ? index : -1))
                .filter((index) => index >= 0);

            if (config.proxies) {
                config.proxies = config.proxies.map((proxy) => {
                    proxy.name = removeLastChar(proxy.name);
                    return proxy;
                });
            }

            config["proxy-groups"].forEach((group) => {
                if (group.proxies) {
                    group.proxies = group.proxies.map(removeLastChar);
                }
            });

            const firstGroup = config["proxy-groups"][0];
            const firstGroupProxies = Array.isArray(firstGroup.proxies) ? firstGroup.proxies : [];

            const japanProxies = japanIndexes.map((index) => firstGroupProxies[index]).filter(Boolean);
            if (japanProxies.length === 0) {
                console.warn(`未匹配到 ${JP_MARK} 日本节点，${JP} 策略组回退为全部节点`);
            }

            // JP 只收日本节点；整份继承 Auto 的 fallback / url / interval /
            // max-connect-times，才会自己测速、参与积分重排，
            // 并在标题后显示 (connectTimes/maxConnectTimes)。
            const jpGroup = {
                ...firstGroup,
                name: JP,
                proxies: japanProxies.length > 0 ? japanProxies : firstGroupProxies,
            };

            config["proxy-groups"].splice(1, 0, jpGroup);

            config["proxy-groups"].slice(2).forEach((group) => {
                // JP 与 Auto 同为节点列表组，勿改写为策略组引用
                if (group.name === JP) {
                    return;
                }
                if (group.proxies && Array.isArray(group.proxies)) {
                    const autoName = config["proxy-groups"][0].name;
                    // 可选的节点组：Auto / JP；新增节点组时在此补充
                    const nodeGroupNames = [autoName, JP];
                    // 按组名定序：Direct 默认 DIRECT；其余（如 Final）默认 Auto
                    if (group.name === DEFAULT_DIRECT || group.name === DIRECT) {
                        group.proxies = ["DIRECT", ...nodeGroupNames];
                    } else {
                        group.proxies = [...nodeGroupNames, "DIRECT"];
                    }
                }
                if (group.name === DEFAULT_DIRECT) {
                    group.name = DIRECT;
                    group.type = "select";
                    delete group.url;
                    delete group.interval;
                    delete group.timeout;
                    delete group.lazy;
                } else if (group.name === DEFAULT_FALLBACK) {
                    group.name = FALLBACK;
                }
            });

            config["proxy-groups"] = reorderProxyGroupsByTemplate(config["proxy-groups"], PROXY_GROUP_ORDER);
        }

        /** raw 规则集分支：DustinWin mihomo-ruleset；custome-rule release（c-*） */
        const RULESET_RAW_BASE = {
            dustinwin: "https://raw.githubusercontent.com/DustinWin/ruleset_geodata/mihomo-ruleset",
            custome: "https://raw.githubusercontent.com/MikeKen-Ken/custome-rule/release",
        };
        /** MetaCubeX/meta-rules-dat（v2fly geosite / geoip 每日生成）：geosite 与 geoip 分目录，URL 单独拼接 */
        const METACUBEX_GEO_BASE = "https://raw.githubusercontent.com/MetaCubeX/meta-rules-dat/meta/geo";

        /** 与 custome-rule release 文件名一致：c-* 自建 */
        const isCustomeRuleset = (name) => name.startsWith("c-");

        const ruleProvidersList = [
            // https://github.com/DustinWin/ruleset_geodata/tree/mihomo-ruleset
            ["fakeip-filter", "fakeip-filter.mrs", "domain", "mrs"],
            ["private", "private.mrs", "domain", "mrs"],
            ["privateip", "privateip.mrs", "ipcidr", "mrs"],
            ["ads", "ads.mrs", "domain", "mrs"],
            ["trackerslist", "trackerslist.mrs", "domain", "mrs"],
            ["games-cn", "games-cn.mrs", "domain", "mrs"],
            ["microsoft-cn", "microsoft-cn.mrs", "domain", "mrs"],
            ["apple-cn", "apple-cn.mrs", "domain", "mrs"],
            ["applications", "applications.list", "classical", "text"],
            ["ai", "ai.mrs", "domain", "mrs"],
            ["cn", "cn.mrs", "domain", "mrs"],
            ["cnip", "cnip.mrs", "ipcidr", "mrs"],

            // fake-ip-filter 专用（dns.fake-ip-filter 引用；c-real-ip 由 CI 从 games 过滤生成）
            ["c-real-ip", "c-real-ip.mrs", "domain", "mrs"],
            ["c-real-ip-kw", "c-real-ip-kw.list", "classical", "text"],

            ["c-reject", "c-reject.mrs", "domain", "mrs"],
            ["c-proc-rej", "c-proc-rej.list", "classical", "text"],
            ["c-proc-wl-dir", "c-proc-wl-dir.list", "classical", "text"],
            ["c-proc-dir", "c-proc-dir.list", "classical", "text"],
            ["c-proc-pxy", "c-proc-pxy.list", "classical", "text"],
            ["c-proc-jp", "c-proc-jp.list", "classical", "text"],
            ["c-proc-google", "c-proc-google.list", "classical", "text"],
            ["c-wl-dir", "c-wl-dir.mrs", "domain", "mrs"],
            ["c-wl-pxy", "c-wl-pxy.mrs", "domain", "mrs"],
            ["c-pxy", "c-pxy.mrs", "domain", "mrs"],
            ["c-jp", "c-jp.mrs", "domain", "mrs"],
            ["c-dir", "c-dir.mrs", "domain", "mrs"],

            // Google 系（MetaCubeX meta-rules-dat）：geosite:google 已含 YouTube / Gemini / Drive / googleapis.cn
            // 第 6 项为完整 URL，覆盖默认 `${base}/${filename}` 拼接
            ["google", "google.mrs", "domain", "mrs", 86400, `${METACUBEX_GEO_BASE}/geosite/google.mrs`],
            ["googleip", "googleip.mrs", "ipcidr", "mrs", 86400, `${METACUBEX_GEO_BASE}/geoip/google.mrs`],
        ];

        const resolveRulesetBase = (name) =>
            isCustomeRuleset(name) ? RULESET_RAW_BASE.custome : RULESET_RAW_BASE.dustinwin;

        // 每个 provider 间隔错开 2 分钟，避免大量 provider 同时到期、集中刷新时持有写锁阻塞连接
        // 元组第 5 项可指定 interval（秒）；Google 系为 86400（每日，与上游每日构建同步）
        config["rule-providers"] = Object.fromEntries(
            ruleProvidersList.map(
                ([name, filename, behavior, format, intervalOverride, urlOverride], index) => {
                    const base = resolveRulesetBase(name);
                    const interval = intervalOverride ?? 28800 + index * 120;
                    return [
                        name,
                        {
                            type: "http",
                            behavior,
                            format,
                            path: `./ruleset/${filename}`,
                            url: urlOverride ?? `${base}/${filename}`,
                            interval,
                        },
                    ];
                }
            )
        );

        /** ipcidr 规则集须 no-resolve，与 rule-providers 的 behavior 一致 */
        const ipCidrRuleSets = new Set(
            ruleProvidersList.filter(([, , behavior]) => behavior === "ipcidr").map(([name]) => name)
        );

        const formatRuleSet = (setName, target) =>
            ipCidrRuleSets.has(setName)
                ? `RULE-SET,${setName},${target},no-resolve`
                : `RULE-SET,${setName},${target}`;

        /**
         * 连接规则流水线：数组顺序即匹配优先级。
         * - reject：拒绝
         * - direct：直连（单集 name 或批量 names）
         * - proxy：走代理（单集 name 或批量 names）
         * - footer：漏网 MATCH
         */
        const RULE_PIPELINE = [
            { kind: "reject", name: "c-reject" },
            { kind: "reject", name: "c-proc-rej" },

            {
                kind: "direct",
                names: ["private", "privateip", "c-proc-wl-dir", "c-wl-dir"],
            },

            { kind: "proxy", name: "c-wl-pxy", target: AUTO },

            // 广告拦截优先于 JP 组：doubleclick 等 Google 广告域名仍走 REJECT
            { kind: "reject", name: "ads" },

            // Google 系：域名 + Google IP 段 + Google 进程 → 仅日本节点
            // 位于 c-dir 之前：想让某个 Google 域名直连，须写进 c-wl-dir（c-dir 不会生效）
            { kind: "proxy", names: ["google", "googleip", "c-proc-google"], target: JP },

            { kind: "direct", names: ["c-proc-dir", "c-dir"] },

            // 进程 / 域名 / AI 三组 → JP（仅日本节点）；
            // 位于 c-dir 之后，写进 c-dir 的域名照旧优先直连
            { kind: "proxy", names: ["c-proc-jp", "c-jp", "ai"], target: JP },
            { kind: "proxy", name: "c-proc-pxy", target: AUTO },
            { kind: "proxy", name: "c-pxy", target: AUTO },

            {
                kind: "direct",
                names: [
                    "applications",
                    "games-cn",
                    "microsoft-cn",
                    "apple-cn",
                    "trackerslist",
                    "cn",
                    "cnip",
                ],
            },

            { kind: "footer" },
        ];

        const pipelineRules = RULE_PIPELINE.flatMap((entry) => {
            switch (entry.kind) {
                case "reject":
                    return [formatRuleSet(entry.name, "REJECT")];
                case "direct": {
                    const names = entry.names ?? [entry.name];
                    return names.map((n) => formatRuleSet(n, DIRECT));
                }
                case "proxy": {
                    const names = entry.names ?? [entry.name];
                    return names.map((n) => formatRuleSet(n, entry.target));
                }
                case "footer":
                    return [`MATCH,${FALLBACK}`];
                default:
                    return [];
            }
        });

        const localRules = LOCAL_TEST_RULES.map(formatLocalTestRule).filter(Boolean);
        config.rules = [...localRules, ...pipelineRules];

        if (config.proxies) {
            config.proxies = config.proxies.map((proxy) => {
                proxy.udp = true;
                return proxy;
            });
        }

        return config;
    } catch (error) {
        console.error("Generate config error:", error);
        return null;
    }
}
