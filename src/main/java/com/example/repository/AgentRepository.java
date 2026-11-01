package com.example.repository;

import com.example.Agent;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import org.hibernate.KeyType;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class AgentRepository implements PanacheRepository<Agent> {

    public Agent findByAgentCode(String agentCode) {
        return getSession().find(Agent.class, agentCode, KeyType.NATURAL);
    }

    public List<Agent> findByAgentCodes(List<String> agentCodes) {
        if (agentCodes == null || agentCodes.isEmpty()) {
            return List.of();
        }
        List<Agent> result = new ArrayList<>(agentCodes.size());
        for (String agentCode : agentCodes) {
            Agent agent = getSession().find(Agent.class, agentCode, KeyType.NATURAL);
            if (agent != null) {
                result.add(agent);
            }
        }
        return result;
    }
}
