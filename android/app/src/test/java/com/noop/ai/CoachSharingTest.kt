package com.noop.ai

import org.junit.Assert.*
import org.junit.Test

class CoachSharingTest {
    @Test fun endpointReapprovalCannotAuthorizeOldInFlightRequest() {
        val old = "CUSTOM|http://localhost:11434/v1"
        val next = "CUSTOM|https://new-server.example/v1"
        assertTrue(CoachSharing.matchesTarget(old, old, old))
        assertFalse(CoachSharing.matchesTarget(null, old, old))
        assertFalse(CoachSharing.matchesTarget(old, next, old))
        // A fresh approval for the new endpoint must not let an old request continue.
        assertFalse(CoachSharing.matchesTarget(next, next, old))
        assertFalse(CoachSharing.matchesTarget("ANTHROPIC|", "ANTHROPIC|", "OPENAI|"))
    }
}
