// Opt-in synthetic S3 smoke test. No database, SSH, deployment or production credentials.
const https = require('node:https');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const HOST = process.env.TEST_ENDPOINT_HOST || '00000000000000000000000000000000.us.r2.cloudflarestorage.com';
const BUCKET = process.env.TEST_BUCKET || 'synthetic-us-verification';
const sha = x => crypto.createHash('sha256').update(x).digest('hex');
const hmac = (key, x) => crypto.createHmac('sha256', key).update(x).digest();
const encode = x => encodeURIComponent(x).replace(/[!'()*]/g, c => '%' + c.charCodeAt(0).toString(16).toUpperCase());
let lastHttpStatus = null;

function signed(method, key, query, payload, credentials, extra = {}) {
  const path = '/' + BUCKET + (key ? '/' + key.split('/').map(encode).join('/') : '');
  const qs = Object.entries(query).sort(([a],[b]) => a.localeCompare(b)).map(([k,v]) => encode(k) + '=' + encode(v)).join('&');
  const timestamp = new Date().toISOString().replace(/[:-]|\.\d{3}/g, '');
  const date = timestamp.slice(0, 8);
  const headers = {host:HOST, 'x-amz-content-sha256':sha(payload), 'x-amz-date':timestamp, ...extra};
  const names = Object.keys(headers).sort();
  const canonical = [method, path, qs, names.map(n => n + ':' + headers[n].trim() + '\n').join(''), names.join(';'), sha(payload)].join('\n');
  const scope = date + '/auto/s3/aws4_request';
  const signing = hmac(hmac(hmac(hmac('AWS4' + credentials.secret, date), 'auto'), 's3'), 'aws4_request');
  headers.authorization = 'AWS4-HMAC-SHA256 Credential=' + credentials.id + '/' + scope + ', SignedHeaders=' + names.join(';') + ', Signature=' + hmac(signing, 'AWS4-HMAC-SHA256\n' + timestamp + '\n' + scope + '\n' + sha(canonical)).toString('hex');
  return {hostname:HOST, method, path:path + (qs ? '?' + qs : ''), headers};
}

function request(method, key, query, payload, credentials, extra) {
  return new Promise((resolve, reject) => {
    const req = https.request(signed(method, key, query, payload, credentials, extra), res => {
      const parts=[]; let size=0;
      res.on('data', part => { size += part.length; if(size > 65536) {req.destroy(); reject(new Error('RESPONSE_LIMIT'));} else parts.push(part); });
      res.on('end', () => { lastHttpStatus=res.statusCode; resolve({status:res.statusCode, headers:res.headers, body:Buffer.concat(parts)}); });
      res.on('error', () => reject(new Error('RESPONSE_FAILED')));
    });
    req.setTimeout(15000, () => req.destroy(new Error('TIMEOUT')));
    req.on('error', () => reject(new Error('REQUEST_FAILED')));
    req.end(payload);
  });
}

function encryptedFixture() {
  const key=crypto.randomBytes(32), iv=crypto.randomBytes(12);
  const plain=Buffer.from(JSON.stringify({synthetic:true, purpose:'us-bucket-verification', fixture:crypto.randomUUID()}));
  const cipher=crypto.createCipheriv('aes-256-gcm',key,iv);
  const ciphertext=Buffer.concat([cipher.update(plain),cipher.final()]);
  const envelope=Buffer.from(JSON.stringify({version:1,iv:iv.toString('base64'),tag:cipher.getAuthTag().toString('base64'),ciphertext:ciphertext.toString('base64')}));
  return {key,plain,envelope};
}
function decrypt(body, key) {
  const obj=JSON.parse(body.toString());
  const cipher=crypto.createDecipheriv('aes-256-gcm',key,Buffer.from(obj.iv,'base64'));
  cipher.setAuthTag(Buffer.from(obj.tag,'base64'));
  return Buffer.concat([cipher.update(Buffer.from(obj.ciphertext,'base64')),cipher.final()]);
}

async function smoke(credentials) {
  const prefix='verification-us-20260908-' + crypto.randomUUID() + '/';
  const objectKey=prefix+'synthetic.bin';
  const fixture=encryptedFixture();
  const result={passed:false, stage:'preflight', checks:[], written:0, deleted:0, objectKey};
  const check=(condition, name) => { if(!condition) throw new Error('CHECK_FAILED'); result.checks.push(name); };
  try {
    const listed=await request('GET','',{'list-type':'2',prefix,'max-keys':'2'},Buffer.alloc(0),credentials);
    check(listed.status===200 && /<KeyCount>0<\/KeyCount>/.test(listed.body.toString()) && !/<IsTruncated>true<\/IsTruncated>/.test(listed.body.toString()), 'new-prefix-empty');
    result.stage='write';
    console.log(JSON.stringify({syntheticObject:objectKey, expectedSha256:sha(fixture.envelope)}));
    const written=await request('PUT',objectKey,{},fixture.envelope,credentials,{'if-none-match':'*','content-type':'application/octet-stream'});
    check(written.status===200,'conditional-write'); result.written=1;
    result.stage='read';
    const read=await request('GET',objectKey,{},Buffer.alloc(0),credentials);
    check(read.status===200 && read.body.equals(fixture.envelope),'encrypted-byte-match');
    check(decrypt(read.body,fixture.key).equals(fixture.plain),'aes-gcm-decrypt-match');
    result.stage='duplicate-guard';
    const duplicate=await request('PUT',objectKey,{},fixture.envelope,credentials,{'if-none-match':'*'});
    check(duplicate.status===412,'overwrite-precondition-rejected');
    result.stage='cleanup-ownership';
    const beforeDelete=await request('GET',objectKey,{},Buffer.alloc(0),credentials);
    check(beforeDelete.status===200 && beforeDelete.body.equals(fixture.envelope),'owned-object-reverified');
    result.stage='cleanup';
    const removed=await request('DELETE',objectKey,{},Buffer.alloc(0),credentials);
    check(removed.status===204,'owned-object-deleted'); result.deleted=1;
    const absent=await request('HEAD',objectKey,{},Buffer.alloc(0),credentials);
    check(absent.status===404,'deleted-object-absent');
    const empty=await request('GET','',{'list-type':'2',prefix,'max-keys':'2'},Buffer.alloc(0),credentials);
    check(empty.status===200 && /<KeyCount>0<\/KeyCount>/.test(empty.body.toString()),'test-prefix-empty');
    result.passed=true;result.stage='complete';
  } catch (_) {
    result.lastHttpStatus=lastHttpStatus;
    // Never include request, credentials, response body or original exception.
    // Preserve any uncertain object for explicit ownership-checked follow-up.
  } finally { fixture.key.fill(0); fixture.plain.fill(0); }
  return result;
}


if (process.argv.includes('--self-test')) {
  const f=encryptedFixture();assert.deepEqual(decrypt(f.envelope,f.key),f.plain);
  assert.throws(()=>decrypt(f.envelope,crypto.randomBytes(32)));
  const s=signed('GET','verification/fixture.bin',{},Buffer.alloc(0),{id:'0'.repeat(32),secret:'1'.repeat(64)});
  assert.equal(s.hostname,HOST);assert.ok(s.path.startsWith('/'+BUCKET+'/verification/'));
  console.log('OFFLINE_ENCRYPTION_AND_TARGET_GUARDS_OK');
} else {
  (async()=>{
    if (process.env.US_SYNTHETIC_CHECK!=='approved' ||
        !/^[a-f0-9]{32}\.us\.r2\.cloudflarestorage\.com$/.test(HOST) ||
        !/^geupddong-account-erasure-ledger-us$/.test(BUCKET))
      throw new Error('TARGET_NOT_APPROVED');
    const credentials={id:process.env.TEST_ACCESS_KEY_ID,secret:process.env.TEST_SECRET_ACCESS_KEY};
    if(!/^[a-f0-9]{32}$/.test(credentials.id||'') || !/^[a-f0-9]{64}$/.test(credentials.secret||''))
      throw new Error('TEST_CREDENTIALS_NOT_CONFIGURED');
    const result=await smoke(credentials);
    credentials.id='';credentials.secret='';
    // Prefix contains only a random synthetic run ID, not member data.
    console.log(JSON.stringify(result));
    if(!result.passed)process.exitCode=1;
  })().catch(()=>{console.error('SYNTHETIC_TEST_ABORTED_WITHOUT_ERROR_DETAILS');process.exitCode=1;});
}
