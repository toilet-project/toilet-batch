package com.example.toiletbatch.account;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ReviewUnlinkRestoreCliTest {
    @Test void bothNonRunningSchedulerModesAreAllowedButActiveOrUnknownModesFail() {
        assertTrue(ReviewUnlinkRestoreCli.schedulerDisabled("OFF"));
        assertTrue(ReviewUnlinkRestoreCli.schedulerDisabled("DISABLED"));
        assertFalse(ReviewUnlinkRestoreCli.schedulerDisabled("ON"));
        assertFalse(ReviewUnlinkRestoreCli.schedulerDisabled(null));
        assertFalse(ReviewUnlinkRestoreCli.schedulerDisabled(""));
    }
}
