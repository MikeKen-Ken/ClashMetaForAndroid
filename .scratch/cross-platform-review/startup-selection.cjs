const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const root=path.resolve('clash-verge-rev');
const ts=require(path.join(root,'node_modules/typescript'));
let selected='';
const exportsObject={};
const noop=async()=>{};
const source=fs.readFileSync(path.join(root,'src/services/proxy-live-connectivity-order.ts'),'utf8');
const code=ts.transpileModule(source,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText;
vm.runInNewContext(code,{exports:exportsObject,require:id=>({
 '@/services/cmds':{applyGroupProxyOrder:noop,clearProxyGroupManualSelection:noop,forceSelectGroupProxy:async(_group,name)=>{selected=name;}},
 '@/services/proxy-connectivity-stats':{hydrateConnectivityStatsFromDisk:noop,buildConnectivityScoreContext:()=>({})},
 '@/services/proxy-region-sort':{sortProxiesByConnectivity:names=>names},
})[id],console,Map,Set,Promise});
(async()=>{
 const members=['high-score-but-offline','verified-healthy'];
 const picker=exportsObject.createDelayTestEarlyPicker({groupName:'Auto',orderedNames:members,timeoutMs:1000});
 picker.onResult(members[0],0);
 picker.onResult(members[1],75);
 await picker.flush();
 const before=selected;
 await exportsObject.stopDelayTestEarlyPickers([picker]);
 await exportsObject.applyStartupLiveConnectivityOrder([{name:'Auto',type:'URLTest',members}]);
 console.log(JSON.stringify({successfulEarlyPick:before,afterStartupFinalization:selected}));
 if(selected!==members[1]){console.error('FAIL: startup pins the failed first-ranked node after verifying a working backup');process.exitCode=1;}
})().catch(error=>{console.error(error);process.exitCode=1;});
