package com.example.service;

import com.example.Agent;
import com.example.repository.AgentRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

@ApplicationScoped
public class AgentService {

    @Inject
    AgentRepository repository;

    public Agent findByAgentCode(String agentCode) {
        return repository.findByAgentCode(agentCode);
    }

    public List<Agent> findByAgentCodes(List<String> agentCodes) {
        return repository.findByAgentCodes(agentCodes);
    }
}
