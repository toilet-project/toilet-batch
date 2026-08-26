-- 기존 어드민 적재 과정에서 카카오 지오코딩으로 생성된 좌표의 출처를 초기화한다.
-- 위도·경도 값은 변경하지 않으며, 처리 시각은 과거 값을 알 수 없어 NULL로 둔다.
UPDATE toilet
   SET coordinate_source = 'GEOCODED_LEGACY',
       geocoded_address_hash = SHA2(
               CONCAT(
                       LOWER(TRIM(COALESCE(road_address, ''))),
                       '|',
                       LOWER(TRIM(COALESCE(jibun_address, '')))
               ),
               256
       ),
       geocoded_at = NULL
 WHERE latitude IS NOT NULL
   AND longitude IS NOT NULL
   AND coordinate_source = 'LEGACY';
