package org.skylark.langur.infrastructure.persistence.jpa.repository;

import org.skylark.langur.infrastructure.persistence.jpa.entity.AgentRunTaskDO;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentRunTaskJpaRepository extends JpaRepository<AgentRunTaskDO, String> {

    List<AgentRunTaskDO> findByAgentId(String agentId);
}
