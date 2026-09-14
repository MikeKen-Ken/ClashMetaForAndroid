const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve('clash-verge-rev');
const ts = require(path.join(root, 'node_modules/typescript'));
const exportsObject = {};
const source = fs.readFileSync(path.join(root,'src/services/delay.ts'),'utf8');
const code = ts.transpileModule(source,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText;
vm.runInNewContext(code,{exports:exportsObject,require:id=>({
 '@/services/proxy-connectivity-stats':{hydrateConnectivityStatsFromDisk:async()=>{}},
 '@/utils/debug':{debugLog:()=>{}},
 './delay-timeout':{raceProxyDelayWithTimeout:async()=>({delay:50})},
})[id],console,Date,Map,Set,Promise,setTimeout,clearTimeout});
const manager=exportsObject.default;
const cached=manager.setDelay('node','group',0);
const snapshot={name:'node',history:[{time:new Date(cached.updatedAt+1000).toISOString(),delay:75}]};
const displayed=manager.getDelayFix(snapshot,'group');
console.log(JSON.stringify({cachedTimeout:0,newerAutomaticResult:75,displayed}));
if(displayed!==75) { console.error('FAIL: newer automatic success is hidden by stale UI timeout'); process.exitCode=1; }
