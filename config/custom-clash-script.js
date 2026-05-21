function main(config) {
    const AUTO = "🔀";
    const DIRECT = "⬆️";
    const FALLBACK = "↩️";
    const NO_HK = "🚫 🇭🇰";
    const HK = "🇭🇰";

    /** 与 FALLBACK 同结构，子链为 AUTO_UDP / NO_HK_UDP / HK_UDP，用于漏网 UDP */
    const FALLBACK_UDP = `${FALLBACK} UDP`;

    /** 与 AUTO / HK / NO_HK 对应，仅含订阅里声明支持 UDP 的节点（见下方 proxy.udp 过滤） */
    const AUTO_UDP = `${AUTO} UDP`;
    const HK_UDP = `${HK} UDP`;
    const NO_HK_UDP = `${NO_HK} UDP`;

    const DEFAULT_AUTO = "🚀 节点选择";
    const DEFAULT_DIRECT = "🎯 全球直连";
    const DEFAULT_FALLBACK = "🐟 漏网之鱼";

    /** 占位：将「未在 PROXY_GROUP_ORDER 中单独列出的组」按当前数组中的相对顺序插入到此处（一般为订阅里其余策略组） */
    const PROXY_GROUP_ORDER_REST = "__REST__";

    /**
     * 最终 proxy-groups 在客户端中的显示顺序。
     * 除 PROXY_GROUP_ORDER_REST 外每一项为 group.name，须与上方 AUTO / NO_HK 等常量一致。
     * DIRECT / FALLBACK 来自订阅里「全球直连」「漏网之鱼」经脚本改名后的 name，不写进列表时等同「未点名」，会走 __REST__ 或末尾兜底。
     */
    const PROXY_GROUP_ORDER = [
        AUTO,
        NO_HK,
        HK,
        AUTO_UDP,
        NO_HK_UDP,
        HK_UDP,
        DIRECT,
        FALLBACK,
        FALLBACK_UDP,
        PROXY_GROUP_ORDER_REST,
    ];

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
                "max-connect-times": 50,
                "expected-status": 204,
                lazy: true,
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

        config["geodata-mode"] = false;
        config["proxy-groups"] = config["proxy-groups"].map(modifyProxyGroup);

        if (config["proxy-groups"].length > 0) {
            const removeLastChar = (name) => {
                if (name.length > 1 && /^[A-Z]$/i.test(name.slice(-1)) && name.slice(-2, -1) !== "-") {
                    return name.slice(0, -1);
                }
                return name;
            };

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

            const newGroups = [
                {
                    name: NO_HK,
                    proxies: firstGroupProxies.filter((proxy) => !proxy.includes("🇭🇰")),
                },
                {
                    name: HK,
                    proxies: firstGroupProxies.filter((proxy) => proxy.includes("🇭🇰")),
                },
            ].map((group) => ({ ...firstGroup, ...group }));

            config["proxy-groups"].splice(1, 0, ...newGroups);

            config["proxy-groups"].slice(3).forEach((group, index) => {
                if (group.proxies && Array.isArray(group.proxies)) {
                    group.proxies = [];
                    if (index === 0) {
                        group.proxies.push(
                            "DIRECT",
                            config["proxy-groups"][0].name,
                            config["proxy-groups"][1].name,
                            config["proxy-groups"][2].name
                        );
                    } else {
                        group.proxies.push(
                            config["proxy-groups"][0].name,
                            config["proxy-groups"][1].name,
                            config["proxy-groups"][2].name,
                            "DIRECT"
                        );
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

            // 为走代理且需 UDP 的策略单独建组：从 AUTO / NO_HK / HK 中剔除 yaml 里 udp: false 的节点（内核无 PROTOCOL 规则，AND 内请用 NETWORK,UDP）
            const proxyByName = new Map(
                (config.proxies || []).filter((p) => p && p.name).map((p) => [p.name, p])
            );
            const supportsUdp = (proxyName) => {
                const p = proxyByName.get(proxyName);
                if (!p) {
                    return true;
                }
                return p.udp !== false;
            };
            const filterUdpProxies = (baseGroup) => {
                const names = Array.isArray(baseGroup.proxies) ? baseGroup.proxies : [];
                const filtered = names.filter(supportsUdp);
                if (filtered.length === 0 && names.length > 0) {
                    console.warn(
                        "UDP proxy group would be empty; keeping unfiltered list. Check proxy.udp in subscription."
                    );
                    return names;
                }
                return filtered;
            };
            const gAuto = config["proxy-groups"][0];
            const gNoHk = config["proxy-groups"][1];
            const gHk = config["proxy-groups"][2];
            const udpSidecars = [
                { ...gAuto, name: AUTO_UDP, proxies: filterUdpProxies(gAuto), "disable-udp": false },
                { ...gNoHk, name: NO_HK_UDP, proxies: filterUdpProxies(gNoHk), "disable-udp": false },
                { ...gHk, name: HK_UDP, proxies: filterUdpProxies(gHk), "disable-udp": false },
            ];
            config["proxy-groups"] = config["proxy-groups"].concat(udpSidecars);

            const fbGroup = config["proxy-groups"].find((g) => g.name === FALLBACK);
            if (fbGroup && Array.isArray(fbGroup.proxies)) {
                const chainToUdp = (p) => {
                    if (p === AUTO) {
                        return AUTO_UDP;
                    }
                    if (p === NO_HK) {
                        return NO_HK_UDP;
                    }
                    if (p === HK) {
                        return HK_UDP;
                    }
                    return p;
                };
                config["proxy-groups"].push({
                    ...fbGroup,
                    name: FALLBACK_UDP,
                    proxies: fbGroup.proxies.map(chainToUdp),
                    "disable-udp": false,
                });
            }

            config["proxy-groups"] = reorderProxyGroupsByTemplate(config["proxy-groups"], PROXY_GROUP_ORDER);
        }

        /** raw 规则集分支：DustinWin mihomo-ruleset；自定义 custome-rule release */
        const RULESET_RAW_BASE = {
            dustinwin: "https://raw.githubusercontent.com/DustinWin/ruleset_geodata/mihomo-ruleset",
            custome: "https://raw.githubusercontent.com/MikeKen-Ken/custome-rule/release",
        };

        const ruleProvidersList = [
            // https://github.com/DustinWin/ruleset_geodata/tree/mihomo-ruleset
            ["fakeip-filter", "fakeip-filter.mrs", "domain", "mrs"],
            ["private", "private.mrs", "domain", "mrs"],
            ["privateip", "privateip.mrs", "ipcidr", "mrs"],
            ["ads", "ads.mrs", "domain", "mrs"],
            ["trackerslist", "trackerslist.mrs", "domain", "mrs"],
            ["spotify", "spotify.mrs", "domain", "mrs"],
            ["microsoft-cn", "microsoft-cn.mrs", "domain", "mrs"],
            ["apple-cn", "apple-cn.mrs", "domain", "mrs"],
            ["games-cn", "games-cn.mrs", "domain", "mrs"],
            ["ai", "ai.mrs", "domain", "mrs"],
            ["cn", "cn.mrs", "domain", "mrs"],
            ["cnip", "cnip.mrs", "ipcidr", "mrs"],

            ["custome-reject", "custome-reject.mrs", "domain", "mrs"],
            ["custome-whitelist-direct", "custome-whitelist-direct.mrs", "domain", "mrs"],
            ["custome-whitelist-proxy", "custome-whitelist-proxy.mrs", "domain", "mrs"],
            ["custome-proxy", "custome-proxy.mrs", "domain", "mrs"],
            ["custome-noHK", "custome-noHK.mrs", "domain", "mrs"],
            ["custome-HK", "custome-HK.mrs", "domain", "mrs"],
            ["custome-direct", "custome-direct.mrs", "domain", "mrs"],
        ];

        // 每个 provider 间隔错开 2 分钟，避免大量 provider 同时到期、集中刷新时持有写锁阻塞连接
        config["rule-providers"] = Object.fromEntries(
            ruleProvidersList.map(([name, filename, behavior, format], index) => {
                const base = name.startsWith("custome-") ? RULESET_RAW_BASE.custome : RULESET_RAW_BASE.dustinwin;
                return [
                    name,
                    {
                        type: "http",
                        behavior,
                        format,
                        path: `./ruleset/${filename}`,
                        url: `${base}/${filename}`,
                        interval: 28800 + index * 120,
                    },
                ];
            })
        );

        /** ipcidr 规则集须 no-resolve，与 rule-providers 的 behavior 一致 */
        const ipCidrRuleSets = new Set(
            ruleProvidersList.filter(([, , behavior]) => behavior === "ipcidr").map(([name]) => name)
        );

        const formatRuleSet = (setName, target) =>
            ipCidrRuleSets.has(setName)
                ? `RULE-SET,${setName},${target},no-resolve`
                : `RULE-SET,${setName},${target}`;

        /** 非 DIRECT：先 UDP 专用组，再 TCP/默认组 */
        const formatProxyRulePair = (setName, tcpTarget, udpTarget) => [
            `AND,((NETWORK,UDP),(RULE-SET,${setName})),${udpTarget}`,
            formatRuleSet(setName, tcpTarget),
        ];

        /**
         * 连接规则流水线：数组顺序即匹配优先级。
         * - reject：拒绝
         * - direct：直连（单集 name 或批量 names）
         * - proxy：走代理，自动生成 UDP 分支
         * - footer：漏网 UDP + MATCH
         */
        const RULE_PIPELINE = [
            { kind: "reject", name: "custome-reject" },
            { kind: "direct", name: "custome-whitelist-direct" },
            { kind: "proxy", name: "custome-whitelist-proxy", tcp: AUTO, udp: AUTO_UDP },

            { kind: "reject", name: "ads" },
            { kind: "direct", names: ["private", "privateip"] },

            { kind: "proxy", name: "custome-HK", tcp: HK, udp: HK_UDP },
            { kind: "proxy", name: "custome-noHK", tcp: NO_HK, udp: NO_HK_UDP },
            { kind: "direct", name: "custome-direct" },

            { kind: "proxy", name: "custome-proxy", tcp: AUTO, udp: AUTO_UDP },
            { kind: "proxy", name: "ai", tcp: NO_HK, udp: NO_HK_UDP },
            { kind: "proxy", name: "spotify", tcp: HK, udp: HK_UDP },

            {
                kind: "direct",
                names: ["games-cn", "microsoft-cn", "apple-cn", "trackerslist", "cn", "cnip"],
            },

            { kind: "footer" },
        ];

        config.rules = RULE_PIPELINE.flatMap((entry) => {
            switch (entry.kind) {
                case "reject":
                    return [formatRuleSet(entry.name, "REJECT")];
                case "direct": {
                    const names = entry.names ?? [entry.name];
                    return names.map((n) => formatRuleSet(n, DIRECT));
                }
                case "proxy":
                    return formatProxyRulePair(entry.name, entry.tcp, entry.udp);
                case "footer":
                    return [`NETWORK,UDP,${FALLBACK_UDP}`, `MATCH,${FALLBACK}`];
                default:
                    return [];
            }
        });

        if (config.proxies) {
            config.proxies = config.proxies.map((proxy) => {
                // proxy.udp = true;
                return proxy;
            });
        }

        return config;
    } catch (error) {
        console.error("Generate config error:", error);
        return null;
    }
}
