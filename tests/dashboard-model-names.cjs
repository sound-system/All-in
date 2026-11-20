const fs=require('fs'),vm=require('vm'),assert=require('assert');
const html=fs.readFileSync('app/src/main/assets/dashboard.html','utf8');
const elements={};let sent;
function element(){return {children:[],style:{},appendChild(x){this.children.push(x)},innerText:'',innerHTML:''};}
const ctx=vm.createContext({document:{createElement:element,getElementById:id=>elements[id]||(elements[id]=element())},zcodeGiftModels:[],executeModelPingTest:x=>sent=x,appConfig:{providers:[{id:'qoder_cn',accounts:[{token:'synthetic-local-fixture'}],modelMeta:{qmodel_38max:{display_name:'Qwen3.8-Max'}}}]},pingModelsOf:()=>['qmodel_38max'],escapeHtml:s=>String(s).replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('"','&quot;')});
const slice=(a,b)=>html.slice(html.indexOf(a),html.indexOf(b,html.indexOf(a)));
vm.runInContext(slice('    function fmtMultiplier(v)','    /** 供应商是否'),ctx);
const pairs={fast:'Fast',lmodel:'QoDuck',qmodel_38max:'Qwen3.8-Max',qfmodel:'Qwen3.8-Flash',dmodel:'DeepSeek-V4-Pro',dfmodel:'DeepSeek-Flash',gmodel:'GLM-5.3',gfmodel:'GLM-5.3-Flash',kmodel_latest:'Kimi-K3.1-Pro',kmodel:'Kimi-K2.8-Preview',jmodel:'Step-5-Preview',mmodel:'MiniMax-M3.1-Flash'};
for(const [id,name]of Object.entries(pairs)){const tag=ctx.buildModelTag('qoder_cn',id,{display_name:name});assert.equal(tag.children[0].innerText,name);assert(tag.title.includes('qoder_cn/'+id));tag.onclick();assert.equal(sent,'qoder_cn/'+id);}
assert.equal(ctx.modelDisplayName('x',{}),'x');assert.equal(ctx.modelDisplayName('x',{display_name:7,name:'Readable'}),'Readable');
const bad='<img src=x onerror=alert(1)>';assert.equal(ctx.buildModelTag('p','x',{display_name:bad}).children[0].innerText,bad);
require('./model-selection-fixture.cjs').loadModelSelection(ctx,html);
vm.runInContext(slice('    function onPingVendorChange(pid, silent)','    /** 测当前下拉'),ctx);
ctx.onPingVendorChange('qoder_cn',true);assert(elements['ping-model-select'].innerHTML.includes('Qwen3.8-Max · qmodel_38max'));assert(elements['ping-model-select'].innerHTML.includes('value="qoder_cn/qmodel_38max"'));
vm.runInContext(slice('    function providerHasCredential(prov)', '    function renderAllModelsTags()'),ctx);
const mini={id:'minimax-code',models:['MiniMax-M3.1-Flash','MiniMax-M3'],accounts:[{access:'fixture'}]};
const media=[['MiniMax-Image','image'],['MiniMax-H3','video'],['MiniMax-H3-Max','video'],['MiniMax-Hailuo-2.3','video']].map(([id,kind])=>({id:'minimax-code/'+id,name:id.replaceAll('-',' '),media_type:kind}));
const card=ctx.providerModelCardCatalog(mini,[...media,media[0]]);
assert.deepEqual(Array.from(card.models),mini.models.concat(media.map(row=>row.id.split('/')[1])));
assert.equal(mini.models.length,2);
ctx.appConfig.miniMaxMediaModels=media;
vm.runInContext(slice('    function providerCounts(p)', '    function providerConfigured(p)'),ctx);
assert.equal(ctx.providerCounts(mini).models,6);
assert.equal(ctx.providerModelCardCatalog({...mini,enabled:false},media).models.length,2);
assert.equal(ctx.providerModelCardCatalog({id:'other',models:['m'],accounts:mini.accounts},media).models.length,1);
ctx.copyText=id=>{sent=id};
for(const row of media){
 const id=row.id.split('/')[1];
 const tag=ctx.buildModelTag(mini.id,id,card.meta[id]);
 assert.equal(tag.textContent,row.name);assert(tag.title.includes(row.id));tag.onclick();assert.equal(sent,row.id);
}
const codex={id:'codex',models:['gpt-5','gpt-image-2.5'],accounts:[{access:'fixture'}]};
ctx.appConfig.codexImageModels=[{id:'codex/gpt-image-2',name:'GPT Image',media_type:'image'}];
const codexCard=ctx.providerModelCardCatalog(codex,ctx.mediaModelsFor('codex'));
assert.deepEqual(Array.from(codexCard.models),['gpt-5','gpt-image-2']);
assert.equal(ctx.providerCounts(codex).models,2);
const drawTag=ctx.buildModelTag('codex','gpt-image-2',codexCard.meta['gpt-image-2']);
assert.equal(drawTag.textContent,'GPT Image');drawTag.onclick();assert.equal(sent,'codex/gpt-image-2');
console.log('Codex card adds one image model and copies its ID PASS');
console.log('MiniMax card includes six models, deduplicates media and copies their IDs PASS');
console.log('model names: 12 official labels, unchanged request IDs, safe text, fallback and selector PASS');
