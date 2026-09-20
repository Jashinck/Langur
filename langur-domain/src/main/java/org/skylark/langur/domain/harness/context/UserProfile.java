package org.skylark.langur.domain.harness.context;

import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.Map;

/**
 * 用户画像 - 权限 / 脱敏开关 / 偏好
 */
@Getter
@Builder
public class UserProfile {

    private final String userId;
    private final String tenantId;
    private final List<String> permissions;
    private final Map<String, Boolean> maskingSwitches;
    private final Map<String, Object> preferences;
}
