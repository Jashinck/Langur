package org.skylark.langur.infrastructure.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "t_agent")
public class AgentDO {

    @Id
    private String id;

    @Column(nullable = false)
    private String name;

    private String description;

    @Lob
    private String systemPrompt;

    private String model;

    private double temperature;

    private int maxIterations;

    private int maxTokens;

    private String status;

    private int iterationCount;

    @Lob
    private String lastError;

    @Lob
    private String conversationHistory;

    @Lob
    private String toolNames;

    private Instant createdAt;

    private Instant updatedAt;
}
