#!/usr/bin/env bash
# Verification only: restores and re-erases in a disposable internal-network MySQL.
# Never promotes a restored DB or changes the live database/Redis/backup files.
set -Eeuo pipefail
phase=preflight
trap 'echo "ERASURE_REHEARSAL_FAILED: phase-$phase" >&2' ERR
umask 077
fail() { echo "ERASURE_REHEARSAL_FAILED: $1" >&2; exit 1; }
[[ $# -eq 1 ]] || fail 'explicit encrypted backup path required'
backup_file="$(realpath -- "$1")"
key_file="${GEUPDDONG_BACKUP_KEY_FILE:?backup decryption key file required}"
tool_dir="${GEUPDDONG_ERASURE_TOOL_DIR:?compiled erasure tool directory required}"
[[ -f "$backup_file" && -r "$backup_file.sha256" && -r "$key_file" && -d "$tool_dir/lib" ]] || fail 'missing input'
[[ "${ERASURE_RESTORE_WRITERS_STOPPED:-}" == true ]] || fail 'freeze ledger writers first'
[[ "${ERASURE_RESTORE_INVENTORY_CONFIRMED:-}" == true ]] || fail 'independent ledger inventory required'
[[ "${ERASURE_RESTORE_EXPECTED_OBJECTS:-}" =~ ^[0-9]{1,7}$ ]] || fail 'expected object count required'
for variable in ERASURE_LEDGER_REALM ERASURE_LEDGER_BUCKET ERASURE_LEDGER_ENDPOINT ERASURE_LEDGER_ACCESS_KEY_ID ERASURE_LEDGER_SECRET_ACCESS_KEY ERASURE_LEDGER_ACTIVE_KEY_ID ERASURE_LEDGER_KEYS_JSON; do
  [[ -n "${!variable:-}" ]] || fail 'missing ledger setting'
done
expected_hash="$(awk 'NR==1 {print $1}' "$backup_file.sha256")"
actual_hash="$(sha256sum "$backup_file" | cut -d ' ' -f 1)"
[[ "$expected_hash" =~ ^[a-fA-F0-9]{64}$ && "${expected_hash,,}" == "$actual_hash" ]] || fail 'backup checksum mismatch'
for program in docker java openssl gzip; do command -v "$program" >/dev/null || fail 'required program missing'; done

run_id="$(openssl rand -hex 16)"
container_name="geupddong-erasure-verify-$run_id"
network_name="geupddong-erasure-net-$run_id"
work_dir="$(mktemp -d -t geupddong-erasure-verify.XXXXXXXX)"
container_id=''
network_id=''
cleanup() {
  local status=$?
  trap - EXIT
  # IDs are returned by this invocation; labels are checked before removing anything.
  if [[ "$container_id" =~ ^[a-f0-9]{64}$ ]] && [[ "$(docker inspect -f '{{index .Config.Labels "geupddong.erasure.verify"}}' "$container_id" 2>/dev/null)" == "$run_id" ]]; then
    docker rm -fv "$container_id" >/dev/null 2>&1 || status=1
  fi
  if [[ "$network_id" =~ ^[a-f0-9]{64}$ ]] && [[ "$(docker network inspect -f '{{index .Labels "geupddong.erasure.verify"}}' "$network_id" 2>/dev/null)" == "$run_id" ]]; then
    docker network rm "$network_id" >/dev/null 2>&1 || status=1
  fi
  rm -f -- "$work_dir/private-errors.log" "$work_dir/dry.out" "$work_dir/apply.out" "$work_dir/after.out"
  rmdir -- "$work_dir" || status=1
  unset MYSQL_PWD ERASURE_RESTORE_DB_PASSWORD
  exit "$status"
}
trap cleanup EXIT
export MYSQL_PWD="$(openssl rand -hex 24)"
export MYSQL_ROOT_PASSWORD="$MYSQL_PWD"
phase=network-create
network_id="$(docker network create --internal --label "geupddong.erasure.verify=$run_id" "$network_name")"
phase=container-create
container_id="$(docker run -d --name "$container_name" --network "$network_name" \
  --label "geupddong.erasure.verify=$run_id" --memory 1g --cpus 1 \
  -p 127.0.0.1::3306 -e MYSQL_ROOT_PASSWORD -e MYSQL_ROOT_HOST=% mysql:8.0.46 \
  --skip-log-bin --event-scheduler=OFF --local-infile=OFF --secure-file-priv=NULL \
  --default-time-zone=+09:00 --max-allowed-packet=1073741824)"
unset MYSQL_ROOT_PASSWORD
phase=container-ready
[[ "$container_id" =~ ^[a-f0-9]{64}$ && "$(docker network inspect -f '{{.Internal}}' "$network_id")" == true ]] || fail 'isolation failed'
for _ in $(seq 1 60); do
  if docker exec -e MYSQL_PWD "$container_id" mysqladmin --protocol=tcp -h127.0.0.1 -uroot ping --silent >/dev/null 2>&1; then break; fi
  sleep 2
done
docker exec -e MYSQL_PWD "$container_id" mysqladmin --protocol=tcp -h127.0.0.1 -uroot ping --silent >/dev/null 2>&1 || fail 'isolated MySQL startup'
mysql_query() { docker exec -i -e MYSQL_PWD "$container_id" mysql -uroot --batch --skip-column-names "$@" 2>>"$work_dir/private-errors.log"; }
phase=backup-import
if ! openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -pass "file:$key_file" -in "$backup_file" 2>>"$work_dir/private-errors.log" \
  | gzip -dc 2>>"$work_dir/private-errors.log" | mysql_query; then fail 'isolated backup import'; fi
[[ "$(mysql_query -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='toilet_db' AND table_name='account_withdrawal';")" == 1 ]] || fail 'V11 schema is required; migrate only the isolated copy first'
[[ "$(mysql_query -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='toilet_db' AND table_name='erasure_restore_guard';")" == 0 ]] || fail 'unexpected pre-existing restore guard'
phase=guard-create
mysql_query -e "CREATE TABLE toilet_db.erasure_restore_guard(marker CHAR(32) NOT NULL PRIMARY KEY); INSERT INTO toilet_db.erasure_restore_guard VALUES('$run_id');"
phase=published-port
port_binding="$(docker port "$container_id" 3306/tcp)"
[[ "$port_binding" =~ ^127\.0\.0\.1:([0-9]{4,5})$ ]] || fail 'restore endpoint is not loopback only'
restore_port="${BASH_REMATCH[1]}"
[[ "$restore_port" != 3306 ]] || fail 'production port refused'
export ERASURE_RESTORE_URL="jdbc:mysql://127.0.0.1:$restore_port/toilet_db"
export ERASURE_RESTORE_MARKER="$run_id"
phase=server-identity
export ERASURE_RESTORE_SERVER_UUID="$(mysql_query -e 'SELECT @@server_uuid;')"
export ERASURE_RESTORE_CONTAINER_ISOLATED=true
export ERASURE_RESTORE_DB_USER=root
export ERASURE_RESTORE_DB_PASSWORD="$MYSQL_PWD"
# This new disposable DB has never been connected to any Redis or application service.
export ERASURE_RESTORE_REDIS_RESET_CONFIRMED=true
phase=before-counts
before_users="$(mysql_query -e 'SELECT COUNT(*) FROM toilet_db.app_user;')"
before_reports="$(mysql_query -e 'SELECT COUNT(*) FROM toilet_db.toilet_report;')"
before_toilets="$(mysql_query -e 'SELECT COUNT(*) FROM toilet_db.toilet;')"
run_tool() { java -cp "$tool_dir/lib/*" com.example.toiletbatch.account.AccountErasureRestoreCli "$@"; }
restore_failure() {
  grep -E '^ERASURE_RESTORE_FAILED: (arguments|database-guard|ledger-configuration|ledger-snapshot|database-replay)$' "$work_dir/private-errors.log" >&2 || true
  fail "$1"
}
phase=dry-run
run_tool --dry-run >"$work_dir/dry.out" 2>>"$work_dir/private-errors.log" || restore_failure 'dry-run; check keyring, inventory and identity'
grep -Eq '^dryRun=true records=[0-9]+ matched=[0-9]+ absent=[0-9]+ erased=0$' "$work_dir/dry.out" || fail 'unexpected dry-run output'
run_tool --apply >"$work_dir/apply.out" 2>>"$work_dir/private-errors.log" || restore_failure 'isolated erasure replay'
run_tool --dry-run >"$work_dir/after.out" 2>>"$work_dir/private-errors.log" || restore_failure 'post-erasure verification'
grep -Eq '^dryRun=true records=[0-9]+ matched=0 absent=[0-9]+ erased=0$' "$work_dir/after.out" || fail 'matched identities remain'
phase=after-counts
after_users="$(mysql_query -e 'SELECT COUNT(*) FROM toilet_db.app_user;')"
after_reports="$(mysql_query -e 'SELECT COUNT(*) FROM toilet_db.toilet_report;')"
after_toilets="$(mysql_query -e 'SELECT COUNT(*) FROM toilet_db.toilet;')"
erased="$(sed -n 's/^dryRun=false records=[0-9]* matched=[0-9]* absent=[0-9]* erased=\([0-9]*\)$/\1/p' "$work_dir/apply.out")"
[[ "$erased" =~ ^[0-9]+$ && "$before_users" -eq $((after_users + erased)) && "$before_reports" == "$after_reports" && "$before_toilets" == "$after_toilets" ]] || fail 'post-erasure count mismatch'
cat "$work_dir/dry.out" "$work_dir/apply.out" "$work_dir/after.out"
echo "ERASURE_REHEARSAL_OK before_users=$before_users after_users=$after_users reports=$after_reports toilets=$after_toilets"
# EXIT cleanup removes this invocation's container and its anonymous data volume. No DB is promoted.
