-- 운영 DB에 적용 완료된 수동 마이그레이션입니다.
-- 기존 행은 coordinate_source = 'LEGACY'로 유지됩니다.
ALTER TABLE toilet
    ADD COLUMN coordinate_source VARCHAR(30) NOT NULL DEFAULT 'LEGACY' AFTER longitude,
    ADD COLUMN geocoded_address_hash CHAR(64) NULL AFTER coordinate_source,
    ADD COLUMN geocoded_at DATETIME NULL AFTER geocoded_address_hash;
