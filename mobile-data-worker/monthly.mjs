const VERSION = /^\d{8}T\d{6}Z-[a-f0-9]{16}$/;
export const OBJECT = /^(full\/\d{8}T\d{6}Z-[a-f0-9]{16}\.sqlite|(delta|daily)\/\d{8}T\d{6}Z-[a-f0-9]{16}\.json|(monthly|intra)\/\d{8}T\d{6}Z-[a-f0-9]{16}\/\d{8}T\d{6}Z-[a-f0-9]{16}\.json)\.gz$/;
const hash = x => /^[a-f0-9]{64}$/.test(x ?? '');
const count = x => Number.isSafeInteger(x) && x > 0;
export function validArtifact(f) {
  return !!f && OBJECT.test(f.key) && count(f.bytes) && f.bytes < 300_000_000 && count(f.gzipBytes) && f.gzipBytes < 64_000_000
    && /^[a-f0-9]{32}$/.test(f.md5 ?? '') && hash(f.sha256) && hash(f.gzipSha256) && Number.isFinite(Date.parse(f.createdAt));
}
function validSnapshot(s) {
  return !!s && VERSION.test(s.version) && count(s.count) && hash(s.contentSha256) && validArtifact(s.full)
    && s.full.key === `full/${s.version}.sqlite.gz`;
}
function validPatch(p, prefix) {
  return validArtifact(p) && VERSION.test(p.fromVersion) && VERSION.test(p.toVersion) && p.fromVersion < p.toVersion && count(p.count)
    && p.key === (prefix === 'daily' ? `daily/${p.toVersion}.json.gz` : `${prefix}/${p.fromVersion}/${p.toVersion}.json.gz`);
}
export function monthlyFiles(m, now = Date.now()) {
  if (!m) return [];
  return [m.checkpoint.full, ...(m.previousCheckpoint ? [m.previousCheckpoint.full] : []), ...m.baselines.map(b => b.full),
    ...m.monthly, ...m.daily, ...(m.intraMonth ? [m.intraMonth] : []),
    ...(m.retained ?? []).filter(r => Date.parse(r.until) > now).map(r => r.artifact)];
}
export function validMonthly(m) {
  if (m?.schemaVersion !== 2 || m.storageVersion !== 1 || !VERSION.test(m.version) || !count(m.count) || !hash(m.contentSha256)
    || !hash(m.revision) || !/^\d{4}-(0[1-9]|1[0-2])$/.test(m.month ?? '') || !Number.isFinite(Date.parse(m.publishedAt))
    || JSON.stringify(m.locales) !== JSON.stringify(['ko','en','ja','zh-cn','zh-tw','zh-hk'])
    || !validSnapshot(m.checkpoint) || m.checkpoint.version > m.version
    || (m.previousCheckpoint && (!validSnapshot(m.previousCheckpoint) || m.previousCheckpoint.version > m.checkpoint.version))
    || !Array.isArray(m.baselines) || !m.baselines.length || m.baselines.length > 128 || !m.baselines.every(validSnapshot)
    || new Set(m.baselines.map(b=>b.version)).size !== m.baselines.length || !m.baselines.some(b=>b.version === m.releaseBaselineVersion)
    || m.baselines.some(b=>b.version > m.version)
    || !Array.isArray(m.monthly) || m.monthly.length > 128 || !Array.isArray(m.daily) || m.daily.length > 100
    || !Array.isArray(m.retained) || m.retained.length > 1000
    || !m.retained.every(r=>validArtifact(r.artifact) && Number.isFinite(Date.parse(r.until)))) return false;
  if (!m.monthly.every(p=>validPatch(p,'monthly') && p.toVersion === m.checkpoint.version && p.count === m.checkpoint.count
    && m.baselines.some(b=>b.version === p.fromVersion)) || new Set(m.monthly.map(p=>p.fromVersion)).size !== m.monthly.length) return false;
  if (m.baselines.some(b=>b.version < m.checkpoint.version && !m.monthly.some(p=>p.fromVersion === b.version))) return false;
  if (!m.daily.every((p,i)=>validPatch(p,'daily') && p.fromVersion >= m.checkpoint.version
    && (!i || m.daily[i-1].toVersion === p.fromVersion))) return false;
  if (m.daily.length && (m.daily.at(-1).toVersion !== m.version || m.daily.at(-1).count !== m.count)) return false;
  return m.checkpoint.version === m.version ? !m.intraMonth && m.checkpoint.count === m.count
    : validPatch(m.intraMonth,'intra') && m.intraMonth.fromVersion === m.checkpoint.version && m.intraMonth.toVersion === m.version && m.intraMonth.count === m.count;
}
