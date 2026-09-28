# Native mobile public catalog

The app reads facility markers, regional counts, names, addresses, opening hours and facilities from SQLite on the device. Reviews, ratings, crowding and tissue availability remain live detail-card requests. Kakao basemap tiles and place search still use the network.

## Contents and languages

The batch service's `MobileCatalogExporter` executes `src/main/resources/mobile-catalog/export.sql` against its existing datasource. It selects explicit public columns for `visibility_status='VISIBLE'` in one MySQL repeatable-read, read-only transaction. It includes current verified region assignments, normalized schedules, coordinate-matched display groups and source-current translations. It never selects users, reviews, credentials or moderation records. All public facilities are retained; only valid Korean coordinates enter the marker index.

Korean plus **five foreign locales** are included: `en`, `ja`, `zh-cn`, **`zh-tw`**, **`zh-hk`**. Taiwan and Hong Kong remain separate records. Missing translations fall back to Korean; the manifest reports actual translation counts.

## Publication

- Dedicated R2 Standard bucket: `geupddong-mobile-data`.
- Public origin: `https://mobile-data.geupddong.com`.
- `latest.json` has a 60-second cache lifetime. Immutable files are compressed with HTTP gzip and cached for 30 days.
- `MobileCatalogScheduler` runs inside the existing **toilet-batch service**, after its 02:00 source import, when configured with `MOBILE_CATALOG_CRON=0 30 4 * * *` (**04:30 KST**). The existing single scheduling thread prevents overlap with the import if it runs long. A missed run is retried at the next scheduled time or through the one-shot CLI below.
- `MOBILE_CATALOG_ENABLED=true`, the cron expression, and secret `MOBILE_CATALOG_PUBLISH_TOKEN` configure the job. The origin defaults to `https://mobile-data.geupddong.com`. The same token is stored as Worker secret `PUBLISH_TOKEN`, never in the app. Both activation and scheduling default to disabled for a controlled migration.
- The image includes Python 3 and the publisher scripts. The service reads public data using its existing JDBC connection, then runs the publisher locally. Temporary exports are removed after each attempt. GitHub runs validation and image builds only; it no longer needs to export production DB data for this job after cutover.
- Identical source content produces no new version. A decrease larger than 20% fails publication for investigation.
- Artifacts are immutable, SHA-256 checked on upload, and verified before the manifest is conditionally committed. A failed or overlapping job cannot publish a manifest pointing to missing files.

## Monthly publication (native app 0.1.46+)

`v2/latest.json` is a separately committed schema-2 index. SQLite and patch payload storage stay at version 1. Its current/previous checkpoints are full snapshots taken on the first successful batch of each Korean calendar month. An unchanged source can reuse the same immutable full artifact when the month changes.

The index carries registered APK baseline snapshots, one cumulative baseline-to-current-checkpoint patch per older baseline, the current month's daily patches, and one checkpoint-to-latest month-to-date patch. Cumulative `monthly/` files are archived indefinitely. Repeated facility edits collapse to their final record; removals include deleted IDs. The `intra/` file is regenerated from the checkpoint, so reverting a change removes it from the net patch rather than leaving stale data.

Clients compare an exact local version with the index. They choose at most two daily files, or reconstruct from an immutable bundled/downloaded baseline with at most two monthly files. A retired, missing, corrupt or uneconomical baseline falls back to the current checkpoint plus the month-to-date patch. A downloaded checkpoint is kept locally to reuse on subsequent launches. Patches must never be overlaid on an arbitrary already-patched database: their `fromVersion` must match the staging database exactly.

The active index protects the current/previous monthly full copies and registered baseline originals. The prior month daily references disappear only after a successful new checkpoint publication; newly unreferenced artifacts are retained for another 48 hours. Cumulative monthly archives are not pruned. Unreferenced failed uploads also receive 48 hours. The legacy `latest.json` remains available with daily deltas and one current full snapshot for already-installed pre-0.1.46 clients; that compatibility full copy is additional to the monthly retention policy.

For an APK/iOS release, run the batch CLI with `--register-baseline`, wait for success, then run the app's `pnpm catalog:bundle`. This packages the exact `releaseBaselineVersion`, not an unregistered transient daily snapshot. Old bases continue to work. An optional `--retire-baseline <exact-version>` removes that baseline from active support; clients automatically download a checkpoint without requiring an APK update. The application keeps locally newer data when the installed APK baseline is older.

Publishing is serialized by a shared file lock in the batch container and protected by independent legacy-version and v2-revision compare-and-swap commits. Artifacts are uploaded and verified before committing their index. A v2 failure leaves the last v2 index usable, even if the compatible legacy publication succeeded. Retries resume from the latest committed indexes. Compression/diff generation happens once on the batch server, never per app download.

## One-shot operations and migration

The CLI uses the batch container's existing database environment and does not start Spring or execute its other jobs. Do not pass tokens or passwords as arguments or print the container environment.

```sh
# Verify the packaged entrypoint without database access or publication.
docker exec toilet-batch java \
  -Dloader.main=com.example.toiletbatch.mobile.MobileCatalogCli \
  -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher \
  --check-runtime

# Publish immediately using the enabled container's configuration.
docker exec toilet-batch java \
  -Dloader.main=com.example.toiletbatch.mobile.MobileCatalogCli \
  -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher

# Append --register-baseline for a new APK base, or
# --retire-baseline <exact-version> to retire a supported base.
```

Cutover order:

1. Review and build the batch image. Use the existing image-only rollout that preserves account lifecycle state; leave catalog scheduling disabled initially. Verify Python and the CLI entrypoint in the running image.
2. Disable `MOBILE_CATALOG_ENABLED` in the **toilet-api GitHub repository** and wait for any in-progress legacy publication to finish. This setting controls the old GitHub exporter, not the Java API runtime. Preserve that workflow temporarily for rollback.
3. Configure the batch container with the four catalog settings above, securely provision its publisher token, and recreate only the batch service. Preserve every other environment setting, volume and API image. GitHub repository variables are not automatically inherited by the running container. Keep secrets in a private environment file, not source control.
4. Run the CLI once, verify `v2/latest.json` and the receipt, then cold-start the app and confirm the schema-2 update route. Legacy `latest.json` continues to be published for old apps.
5. If activation fails, disable the batch schedule before restoring the old GitHub job. Keep the last valid data published; never enable both publishers at once. If a credential was rotated, rollback also requires updating the old workflow's secret to the matching value.

The generic `deploy.yml` recreates base environment files. It is not the migration path: use the existing preserving rollout and retain the catalog settings on future deployments. No API image deployment, DB migration or website deployment is required.

Migration status on 2026-09-28: the local batch implementation and tests are ready. The compatible Worker is deployed, but the production publisher still runs from the API repository until the controlled cutover is approved and executed. A local code move alone does not change the active job.

## Legacy retention and recovery

The legacy index retains its current full snapshot and 30 days of deltas. V2 protects monthly checkpoints and active baseline originals independently. Deltas include full replacement records and deleted IDs, so translation removals, visibility removals and facility changes all propagate. Pruning removes expired legacy delta references even on unchanged days. The current snapshot has no age-based expiry.

At cold launch the app immediately restores the last validated SQLite file and checks the manifest once. It uses a complete delta chain only when cheaper than a full download; expired/missing chains fall back to the latest full snapshot. Updates run on a staging copy, check file checksum, database integrity, version and count, then atomically change the saved pointer. Previously downloaded data remains usable on failure. Returning from the background does not re-sync.

Disable `MOBILE_CATALOG_ENABLED` to pause future exports. Existing public files and installed offline copies continue working. This pipeline does not deploy or modify the API service or existing web cache.

## Validation

```text
python -B -m unittest discover -s scripts/mobile-data -p 'test_*.py'
node --test mobile-data-worker/worker.test.mjs
./gradlew test --tests 'com.example.toiletbatch.mobile.*' bootJar
```

App checks live in `toilet-mobile/tests/catalog.test.mjs`, `catalog-monthly.test.mjs` and `catalog-storage.test.mjs`, alongside native app type checks and emulator validation.

Initial production verification on 2026-09-27: 51,918 public facilities, including 50,631 with supported map coordinates. The 32 KiB SQLite page layout uses 134,119,424 bytes on disk and 15,860,179 bytes compressed for the first download. Regional map counts include mapped facilities only; all public details remain in the catalog.

The app's `catalog-storage.test.mjs` also exercises actual SQLite transactions, cold restores, deletions, failed delta/full-download recovery and offline reads. Android 0.1.27 successfully downloaded and opened the first production snapshot; native iOS installation still requires Apple signing.
