import { OBJECT, monthlyFiles, validMonthly } from './monthly.mjs';
const VERSION = /^\d{8}T\d{6}Z-[a-f0-9]{16}$/;
const LOCALES = ['ko','en','ja','zh-cn','zh-tw','zh-hk'];
const json = (value, status=200) => Response.json(value, { status, headers: { 'Cache-Control':'no-store' } });
export function validManifest(m) {
  if (m?.schemaVersion!==1 || !VERSION.test(m.version) || !Number.isSafeInteger(m.count) || m.count<1
    || JSON.stringify(m.locales)!==JSON.stringify(LOCALES) || !Array.isArray(m.deltas) || m.deltas.length>100) return false;
  const files=[m.full,...(m.previousFull?[m.previousFull]:[]),...m.deltas];
  if (!files.every(f=>OBJECT.test(f?.key) && f.bytes>0 && f.bytes<300_000_000 && f.gzipBytes>0 && f.gzipBytes<64_000_000
    && /^[a-f0-9]{64}$/.test(f.sha256) && /^[a-f0-9]{64}$/.test(f.gzipSha256) && /^[a-f0-9]{32}$/.test(f.md5))) return false;
  if (m.full.key!==`full/${m.version}.sqlite.gz`) return false;
  return m.deltas.every((d,i)=>VERSION.test(d.fromVersion) && VERSION.test(d.toVersion) && d.key===`delta/${d.toVersion}.json.gz`
    && d.fromVersion<d.toVersion && (!i || m.deltas[i-1].toVersion===d.fromVersion))
    && (!m.deltas.length || m.deltas.at(-1).toVersion===m.version);
}
async function authorized(request,secret) {
  if (!secret) return false;
  const digest=async s=>new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(s)));
  const a=await digest(request.headers.get('Authorization')??''), b=await digest(`Bearer ${secret}`);
  let difference=0; for(let i=0;i<a.length;i++) difference|=a[i]^b[i];
  return difference===0;
}
export default {
  async fetch(request,env,ctx) {
    const url=new URL(request.url), path=url.pathname.slice(1), bucket=env.CATALOG;
    if (path.startsWith('admin/')) {
      if (!await authorized(request,env.PUBLISH_TOKEN)) return json({error:'Unauthorized'},401);
      if (path==='admin/monthly/latest' && request.method==='GET') {
        const object=await bucket.get('v2/latest.json');
        return object ? new Response(object.body,{headers:{'Content-Type':'application/json','Cache-Control':'no-store'}}) : json({error:'Not found'},404);
      }
      if (path==='admin/monthly/commit' && request.method==='POST') {
        if (Number(request.headers.get('Content-Length'))>2_000_000) return json({error:'Too large'},413);
        const m=await request.json();
        if (!validMonthly(m)) return json({error:'Invalid monthly manifest'},400);
        const previous=await bucket.get('v2/latest.json'), old=previous ? await previous.json() : null;
        if ((request.headers.get('X-Previous-Revision')||null)!==(old?.revision??null) || (old && m.version<old.version)) return json({error:'Revision changed'},409);
        for (const f of new Map(monthlyFiles(m).map(f=>[f.key,f])).values()) {
          const head=await bucket.head(f.key);
          if (!head || head.size!==f.gzipBytes || head.customMetadata?.sha256!==f.gzipSha256) return json({error:'Missing or invalid artifact'},409);
        }
        const saved=await bucket.put('v2/latest.json',JSON.stringify(m),{onlyIf:previous?{etagMatches:previous.etag}:{etagDoesNotMatch:'*'},httpMetadata:{contentType:'application/json'}});
        return saved ? json({version:m.version,revision:m.revision}) : json({error:'Concurrent publication'},409);
      }
      if (path==='admin/latest' && request.method==='GET') {
        const object=await bucket.get('latest.json');
        return object ? new Response(object.body,{headers:{'Content-Type':'application/json','Cache-Control':'no-store'}}) : json({error:'Not found'},404);
      }
      if (path.startsWith('admin/object/') && request.method==='PUT') {
        const key=path.slice('admin/object/'.length), size=Number(request.headers.get('Content-Length'));
        const sha256=request.headers.get('X-Content-SHA256');
        if (!OBJECT.test(key) || size<=0 || size>64_000_000 || !/^[a-f0-9]{64}$/.test(sha256??'')) return json({error:'Invalid upload'},400);
        const existing=await bucket.head(key);
        if (existing) return existing.customMetadata?.sha256===sha256 ? json({stored:true}) : json({error:'Immutable key conflict'},409);
        await bucket.put(key,request.body,{sha256,onlyIf:{etagDoesNotMatch:'*'},customMetadata:{sha256},httpMetadata:{contentType:key.startsWith('full/')?'application/vnd.sqlite3':'application/json',contentEncoding:'gzip'}});
        return json({stored:true});
      }
      if (path==='admin/commit' && request.method==='POST') {
        if (Number(request.headers.get('Content-Length'))>100_000) return json({error:'Too large'},413);
        const m=await request.json();
        if (!validManifest(m)) return json({error:'Invalid manifest'},400);
        const previous=await bucket.get('latest.json'), old=previous ? await previous.json() : null;
        if ((request.headers.get('X-Previous-Version')||null)!==(old?.version??null)) return json({error:'Version changed'},409);
        if (old && m.version<=old.version) return json({error:'Version must increase'},409);
        for (const f of [m.full,...(m.previousFull?[m.previousFull]:[]),...m.deltas]) {
          const head=await bucket.head(f.key);
          if (!head || head.size!==f.gzipBytes || head.customMetadata?.sha256!==f.gzipSha256) return json({error:'Missing or invalid artifact'},409);
        }
        const saved=await bucket.put('latest.json',JSON.stringify(m),{onlyIf:previous?{etagMatches:previous.etag}:{etagDoesNotMatch:'*'},httpMetadata:{contentType:'application/json'}});
        return saved ? json({version:m.version}) : json({error:'Concurrent publication'},409);
      }
      if (path==='admin/prune' && request.method==='POST') {
        let current=await bucket.get('latest.json');
        if (!current) return json({error:'No manifest'},409);
        let m=await current.json();
        const deltas=m.deltas.filter(d=>Date.parse(d.createdAt)>=Date.now()-30*86400_000);
        if(deltas.length!==m.deltas.length) {
          const saved=await bucket.put('latest.json',JSON.stringify({...m,deltas}),{onlyIf:{etagMatches:current.etag},httpMetadata:{contentType:'application/json'}});
          if(!saved) return json({error:'Version changed'},409);
          current=await bucket.get('latest.json'); m=await current.json();
        }
        const monthlyObject=await bucket.get('v2/latest.json'), monthly=monthlyObject ? await monthlyObject.json() : null;
        const keep=new Set([m.full.key,m.previousFull?.key,...m.deltas.map(d=>d.key),...monthlyFiles(monthly).map(f=>f.key)]);
        let removed=0;
        // monthly/ is an archive, deliberately never subject to age-based deletion.
        for(const prefix of ['full/','delta/','daily/','intra/']) {
          let cursor;
          do {
            const page=await bucket.list({prefix,cursor,limit:500});
            if ((await bucket.head('latest.json'))?.etag!==current.etag) return json({error:'Version changed'},409);
            if ((await bucket.head('v2/latest.json'))?.etag!==monthlyObject?.etag) return json({error:'Monthly revision changed'},409);
            const obsolete=page.objects.filter(o=>OBJECT.test(o.key) && !keep.has(o.key) && o.uploaded.getTime()<Date.now()-48*3600_000).map(o=>o.key);
            if(obsolete.length) { await bucket.delete(obsolete); removed+=obsolete.length; }
            cursor=page.truncated?page.cursor:undefined;
          } while(cursor);
        }
        return json({removed});
      }
      return json({error:'Not found'},404);
    }
    const isManifest=path==='latest.json'||path==='v2/latest.json';
    if (!['GET','HEAD'].includes(request.method) || (!isManifest && !OBJECT.test(path))) return json({error:'Not found'},404);
    const cacheKey=new Request(url.origin+'/'+path), cache=caches.default;
    const cached=await cache.match(cacheKey);
    if(cached) return request.method==='HEAD'?new Response(null,cached):cached;
    const object=await bucket.get(path);
    if(!object) return json({error:'Not found'},404);
    const headers=new Headers(); object.writeHttpMetadata(headers);
    headers.set('ETag',object.httpEtag); headers.set('X-Content-Type-Options','nosniff');
    headers.set('Cache-Control',isManifest?'public,max-age=60':'public,max-age=2592000,immutable');
    const response=new Response(object.body,{headers,encodeBody:'manual'});
    ctx.waitUntil(cache.put(cacheKey,response.clone()));
    return request.method==='HEAD'?new Response(null,{headers}):response;
  }
};
