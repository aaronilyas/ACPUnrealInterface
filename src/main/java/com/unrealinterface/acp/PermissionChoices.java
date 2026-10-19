package com.unrealinterface.acp;

import java.util.List;

import com.agentclientprotocol.sdk.spec.AcpSchema.PermissionOption;
import com.agentclientprotocol.sdk.spec.AcpSchema.PermissionOptionKind;
import com.agentclientprotocol.sdk.spec.AcpSchema.RequestPermissionRequest;

public final class PermissionChoices {

    private PermissionChoices() {
    }

    /**
     * The allow-once option, or null. Auto-approve uses this and never falls
     * through to allow-always, which would persist a grant the user did not confirm.
     */
    public static String allowOnce(List<PermissionOption> options) {
        if (options == null || options.isEmpty()) {
            return null;
        }
        for (PermissionOption option : options) {
            if (option.kind() == PermissionOptionKind.ALLOW_ONCE) {
                return option.optionId();
            }
        }
        return null;
    }

    /**
     * Prefers a one-shot allow over an allow-always option. Returns null when the
     * agent offered no allow option. Callers that grant silently must use
     * {@link #allowOnce} instead of this fallback.
     */
    public static String preferredAllow(List<PermissionOption> options) {
        if (options == null || options.isEmpty()) {
            return null;
        }
        String allowAlways = null;
        for (PermissionOption option : options) {
            String kind = option.kind() == null ? "" : option.kind().value();
            if ("allow_once".equals(kind)) {
                return option.optionId();
            }
            if ("allow_always".equals(kind) && allowAlways == null) {
                allowAlways = option.optionId();
            }
        }
        return allowAlways;
    }

    public static String summary(RequestPermissionRequest request) {
        var call = request.toolCall();
        StringBuilder text = new StringBuilder();
        if (call.name() != null && !call.name().isBlank()) {
            text.append(call.name()).append(" — ");
        }
        if (call.title() != null && !call.title().isBlank()) {
            text.append(call.title());
        }
        if (call.kind() != null) {
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append('[').append(call.kind().value()).append(']');
        }
        if (text.length() == 0) {
            text.append(call.toolCallId());
        }
        return text.toString();
    }
}
