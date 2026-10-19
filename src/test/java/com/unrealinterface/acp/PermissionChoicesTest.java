package com.unrealinterface.acp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.agentclientprotocol.sdk.spec.AcpSchema.PermissionOption;
import com.agentclientprotocol.sdk.spec.AcpSchema.PermissionOptionKind;

class PermissionChoicesTest {

    @Test
    void prefersAllowOnceOverAllowAlways() {
        var options = List.of(
                new PermissionOption("always", "Always", PermissionOptionKind.ALLOW_ALWAYS),
                new PermissionOption("once", "Once", PermissionOptionKind.ALLOW_ONCE),
                new PermissionOption("no", "No", PermissionOptionKind.REJECT_ONCE));
        assertEquals("once", PermissionChoices.preferredAllow(options));
    }

    @Test
    void returnsNullWhenNothingAllows() {
        var options = List.of(new PermissionOption("no", "No", PermissionOptionKind.REJECT_ONCE));
        assertNull(PermissionChoices.preferredAllow(options));
    }
}
