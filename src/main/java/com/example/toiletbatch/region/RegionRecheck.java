package com.example.toiletbatch.region;

import java.util.ArrayList;
import static com.example.toiletbatch.region.RegionModel.*;

/** Conservative corroboration only: never replaces the source coordinates or addresses. */
public final class RegionRecheck {
    public static final double MAX_DISTANCE_METERS = 50;
    private final RegionProvider provider;
    public RegionRecheck(RegionProvider provider) { this.provider = provider; }

    public record Outcome(boolean verified, boolean providerFailed, Evidence evidence) { }
    public Outcome check(Source source, Point point, Region region) {
        var reasons = new ArrayList<String>();
        var addresses = new ArrayList<AddressEvidence>();
        ReverseAddress reverse = null;
        boolean providerFailed = false;
        try {
            reverse = provider.reverseAddress(point);
            if (reverse == null || new AddressRegionCheck().check(reverse.sidoName() +
                    (reverse.sigunguName() == null ? "" : " " + reverse.sigunguName()), region) != Check.MATCH)
                reasons.add("PROVIDER_REGION_DISAGREEMENT");
            if (reverse != null && reverse.roadAddress() != null &&
                    new AddressRegionCheck().check(reverse.roadSidoName() +
                            (reverse.roadSigunguName() == null ? "" : " " + reverse.roadSigunguName()), region) != Check.MATCH)
                reasons.add("REVERSE_ROAD_REGION_DISAGREEMENT");
        } catch (RegionProvider.Failure e) { reasons.add(e.getMessage()); providerFailed = true; }
        String[] fields = {"ROAD", "JIBUN"};
        String[] inputs = {source.roadAddress(), source.jibunAddress()};
        for (int i = 0; i < inputs.length; i++) {
            if (inputs[i] == null || inputs[i].isBlank()) continue;
            AddressLookup lookup = null;
            Double distance = null;
            String reason;
            try {
                lookup = provider.searchAddress(inputs[i]);
                if (lookup == null) { reason = "ADDRESS_RECHECK_UNAVAILABLE"; providerFailed = true; }
                else if (lookup.totalCount() == 0) reason = "NO_ADDRESS_RESULT";
                else if (lookup.totalCount() != 1) reason = "AMBIGUOUS_ADDRESS";
                else if (lookup.point() == null || !lookup.point().valid()) reason = "INVALID_ADDRESS_COORDINATE";
                else {
                    distance = distanceMeters(point, lookup.point());
                    String code = lookup.legalDongCode();
                    if (code == null || !code.matches("[1-9][0-9]{9}")) reason = "INVALID_ADDRESS_CODE";
                    else if (!code.substring(0, 5).equals(region.sigunguCode())) reason = "ADDRESS_CODE_CONFLICT";
                    else if (distance > MAX_DISTANCE_METERS) reason = "ADDRESS_DISTANCE_EXCEEDED";
                    else reason = "MATCH";
                }
            } catch (RegionProvider.Failure e) { reason = e.getMessage(); providerFailed = true; }
            addresses.add(new AddressEvidence(fields[i], lookup, distance, reason));
            if (!"MATCH".equals(reason)) reasons.add(fields[i] + ":" + reason);
        }
        if (addresses.isEmpty()) reasons.add("NO_ADDRESS_EVIDENCE");
        return new Outcome(reasons.isEmpty(), providerFailed,
                new Evidence(reverse, MAX_DISTANCE_METERS, addresses, reasons));
    }
    static double distanceMeters(Point a, Point b) {
        double lat1 = Math.toRadians(a.latitude().doubleValue()), lat2 = Math.toRadians(b.latitude().doubleValue());
        double dy = lat2 - lat1, dx = Math.toRadians(b.longitude().doubleValue() - a.longitude().doubleValue());
        double h = Math.pow(Math.sin(dy / 2), 2) + Math.cos(lat1) * Math.cos(lat2) * Math.pow(Math.sin(dx / 2), 2);
        return 6371000 * 2 * Math.asin(Math.sqrt(Math.min(1, Math.max(0, h))));
    }
}
