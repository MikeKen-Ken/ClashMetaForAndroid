function main(config) {
    const AUTO = "🔀";
    const DIRECT = "⬆️";
    const FALLBACK = "↩️";
    const NO_HK = "🚫 🇭🇰";
    const HK = "🇭🇰";

    const DEFAULT_AUTO = "🚀 节点选择";
    const DEFAULT_DIRECT = "🎯 全球直连";
    const DEFAULT_FALLBACK = "🐟 漏网之鱼";

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
        }

        const ruleProvidersList = [
            // https://github.com/DustinWin/ruleset_geodata/tree/mihomo-geodata           
            ["fakeip-filter", "fakeip-filter.mrs", "domain", "mrs"],
            ["private", "private.mrs", "domain", "mrs"],
            ["privateip", "privateip.mrs", "ipcidr", "mrs"],
            ["ads", "ads.mrs", "domain", "mrs"],
            ["trackerslist", "trackerslist.mrs", "domain", "mrs"],
            ["applications", "applications.list", "classical", "text"],
            ["microsoft-cn", "microsoft-cn.mrs", "domain", "mrs"],
            ["apple-cn", "apple-cn.mrs", "domain", "mrs"],
            ["google-cn", "google-cn.mrs", "domain", "mrs"],
            ["games-cn", "games-cn.mrs", "domain", "mrs"],
            ["spotify", "spotify.mrs", "domain", "mrs"],
            ["ai", "ai.mrs", "domain", "mrs"],
            ["networktest", "networktest.mrs", "domain", "mrs"],
            ["cn", "cn.mrs", "domain", "mrs"],
            ["cnip", "cnip.mrs", "ipcidr", "mrs"],

            ["custome-reject", "custome-reject.yaml", "classical", "yaml"],
            ["custome-whitelist-direct", "custome-whitelist-direct.yaml", "classical", "yaml"],
            ["custome-whitelist-proxy", "custome-whitelist-proxy.yaml", "classical", "yaml"],
            ["custome-proxy", "custome-proxy.yaml", "classical", "yaml"],
            ["custome-noHK", "custome-noHK.yaml", "classical", "yaml"],
            ["custome-HK", "custome-HK.yaml", "classical", "yaml"],
            ["custome-direct", "custome-direct.yaml", "classical", "yaml"],
        ];

        // 每个 provider 间隔错开 2 分钟，避免 30 个 provider 同时到期、集中刷新时持有写锁阻塞连接
        config["rule-providers"] = Object.fromEntries(
            ruleProvidersList.map(([name, filename, behavior, format], index) => {
                const interval = 28800 + index * 120;
                if (name.startsWith("custome-")) {
                    return [
                        name,
                        {
                            type: "http",
                            behavior,
                            format,
                            path: `./ruleset/${filename}`,
                            url: `https://raw.githubusercontent.com/MikeKen-Ken/custome-rule/main/${filename}`,
                            interval,
                        },
                    ];
                }
                return [
                    name,
                    {
                        type: "http",
                        behavior,
                        format,
                        path: `./ruleset/${filename}`,
                        url: `https://raw.githubusercontent.com/DustinWin/ruleset_geodata/mihomo-ruleset/${filename}`,
                        interval,
                    },
                ];
            })
        );

        config.rules = [
            `RULE-SET,custome-reject,REJECT`,

            `RULE-SET,custome-whitelist-direct,${DIRECT}`,
            `RULE-SET,custome-whitelist-proxy,${AUTO}`,

            `RULE-SET,private,${DIRECT}`,
            `RULE-SET,privateip,${DIRECT},no-resolve`,
            `RULE-SET,trackerslist,REJECT`,
            `RULE-SET,ads,REJECT`,

            `RULE-SET,custome-noHK,${NO_HK}`,
            `RULE-SET,custome-HK,${HK}`,
            `RULE-SET,custome-proxy,${AUTO}`,
            `RULE-SET,custome-direct,${DIRECT}`,

            `RULE-SET,spotify,${HK}`,
            `RULE-SET,ai,${NO_HK}`,

            `RULE-SET,networktest,${DIRECT}`,
            `RULE-SET,applications,${DIRECT}`,
            `RULE-SET,microsoft-cn,${DIRECT}`,
            `RULE-SET,apple-cn,${DIRECT}`,
            `RULE-SET,games-cn,${DIRECT}`,

            `RULE-SET,cn,${DIRECT}`,

            `RULE-SET,cnip,${DIRECT},no-resolve`,

            `MATCH,${FALLBACK}`,
        ];

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
