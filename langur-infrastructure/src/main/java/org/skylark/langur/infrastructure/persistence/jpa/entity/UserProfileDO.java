package org.skylark.langur.infrastructure.persistence.jpa.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * t_user_profile - 用户画像（权限 + 脱敏开关 + 偏好）（§12.3）。
 */
@Getter
@Setter
@Entity
@Table(name = "t_user_profile", indexes = @Index(name = "idx_profile_tenant_id", columnList = "tenantId"))
public class UserProfileDO {

    @Id
    private String userId;

    private String tenantId;

    @Lob
    private String permissions;

    private boolean maskingEnabled;

    @Lob
    private String preferences;

    private Instant updatedAt;
}
