SELECT JSON_OBJECT('kind','meta','count',COUNT(*)) FROM toilet WHERE visibility_status='VISIBLE';
SELECT JSON_OBJECT('kind','toilet','value',JSON_OBJECT(
  'id',t.toilet_id,'name',t.name,'latitude',t.latitude,'longitude',t.longitude,
  'toiletType',t.toilet_type,'roadAddress',t.road_address,'jibunAddress',t.jibun_address,
  'maleToiletCount',t.male_toilet_count,'maleUrinalCount',t.male_urinal_count,
  'maleDisabledToiletCount',t.male_disabled_toilet_count,'maleDisabledUrinalCount',t.male_disabled_urinal_count,
  'maleChildToiletCount',t.male_child_toilet_count,'maleChildUrinalCount',t.male_child_urinal_count,
  'femaleToiletCount',t.female_toilet_count,'femaleDisabledToiletCount',t.female_disabled_toilet_count,
  'femaleChildToiletCount',t.female_child_toilet_count,
  'agencyName',t.agency_name,'phoneNumber',t.phone_number,'openTime',t.open_time,'openTimeDetail',t.open_time_detail,
  'installationDate',t.installation_date,'hasEmergencyBell',t.has_emergency_bell,
  'emergencyBellLocation',t.emergency_bell_location,'hasCctv',t.has_cctv,'hasDiaperTable',t.has_diaper_table,
  'diaperTableLocation',t.diaper_table_location,'dataBaseDate',t.data_base_date,'dataSource',t.data_source,
  'region',IF(r.toilet_id IS NULL,NULL,JSON_OBJECT('sidoCode',r.sido_code,'sidoName',r.sido_name,
     'sigunguCode',r.sigungu_code,'sigunguName',r.sigungu_name,'cityName',r.city_name,'districtName',r.district_name)),
  'displayGroupId',g.group_id,'displayGroupName',g.display_name))
FROM toilet t LEFT JOIN current_toilet_region r ON r.toilet_id=t.toilet_id
LEFT JOIN toilet_display_group_member gm ON gm.toilet_id=t.toilet_id
LEFT JOIN toilet_display_group g ON g.group_id=gm.group_id AND g.latitude=t.latitude AND g.longitude=t.longitude
WHERE t.visibility_status='VISIBLE' ORDER BY t.toilet_id;
SELECT JSON_OBJECT('kind','translation','id',tr.toilet_id,'locale',tr.locale,'value',JSON_OBJECT(
  'name',tr.name,'roadAddress',tr.road_address,'jibunAddress',tr.jibun_address))
FROM toilet_translation tr JOIN toilet t ON t.toilet_id=tr.toilet_id AND t.visibility_status='VISIBLE'
JOIN toilet_translation ko ON ko.toilet_id=tr.toilet_id AND ko.locale='ko'
WHERE tr.locale IN ('en','ja','zh-cn','zh-tw','zh-hk') AND tr.source_hash=ko.source_hash
  AND ko.source_hash=SHA2(CONCAT(COALESCE(TRIM(t.name),''),CHAR(31),COALESCE(TRIM(t.road_address),''),CHAR(31),COALESCE(TRIM(t.jibun_address),'')),256)
ORDER BY tr.toilet_id,tr.locale;
SELECT JSON_OBJECT('kind','groupTranslation','id',t.toilet_id,'locale',tr.locale,'value',tr.display_name)
FROM toilet t JOIN toilet_display_group_member gm ON gm.toilet_id=t.toilet_id
JOIN toilet_display_group g ON g.group_id=gm.group_id AND g.latitude=t.latitude AND g.longitude=t.longitude
JOIN toilet_display_group_translation tr ON tr.group_id=g.group_id
WHERE t.visibility_status='VISIBLE' AND tr.locale IN ('en','ja','zh-cn','zh-tw','zh-hk')
  AND (BINARY tr.source_name=BINARY g.display_name OR tr.manual_override=TRUE)
ORDER BY t.toilet_id,tr.locale;
SELECT JSON_OBJECT('kind','hours','id',h.toilet_id,'value',JSON_OBJECT(
  'openingPolicy',h.opening_policy,'open24h',h.is_open_24h,'status',h.normalization_status,
  'confidence',h.confidence,'parserVersion',h.parser_version,'holidayPolicy',h.holiday_policy,
  'manualOverride',h.manual_override,'sourceChanged',h.source_changed))
FROM toilet_opening_hours h JOIN toilet t ON t.toilet_id=h.toilet_id WHERE t.visibility_status='VISIBLE' ORDER BY h.toilet_id;
SELECT JSON_OBJECT('kind','schedule','id',s.toilet_id,'value',JSON_OBJECT(
  'dayOfWeek',s.day_of_week,'slotIndex',s.slot_index,'startTime',TIME_FORMAT(s.start_time,'%H:%i'),
  'endTime',TIME_FORMAT(s.end_time,'%H:%i'),'crossesMidnight',s.crosses_midnight,'closed',s.is_closed))
FROM toilet_opening_schedule s JOIN toilet t ON t.toilet_id=s.toilet_id WHERE t.visibility_status='VISIBLE'
ORDER BY s.toilet_id,s.day_of_week,s.slot_index;
SELECT JSON_OBJECT('kind','complete','count',COUNT(*)) FROM toilet WHERE visibility_status='VISIBLE';
