package com.example.toiletbatch.region;

import java.util.Optional;
import static com.example.toiletbatch.region.RegionModel.*;

public interface RegionProvider {
    Optional<Point> geocodeUnique(String address);
    Region reverse(Point point);
    default AddressLookup searchAddress(String address) { throw new Failure("ADDRESS_RECHECK_UNAVAILABLE"); }
    default ReverseAddress reverseAddress(Point point) { throw new Failure("ADDRESS_RECHECK_UNAVAILABLE"); }

    /** Abort the run on quota/auth failures; never mark all subsequent toilets as bad data. */
    class Stop extends RuntimeException { public Stop(String safeMessage) { super(safeMessage); } }
    class Failure extends RuntimeException { public Failure(String safeCode) { super(safeCode); } }
}
