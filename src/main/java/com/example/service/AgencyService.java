package com.example.service;

import com.example.Agency;
import com.example.repository.AgencyRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class AgencyService {

    @Inject
    AgencyRepository repository;

    public List<Agency> findAll() {
        return repository.listAll();
    }

    public Agency findById(Long id) {
        return repository.findById(id);
    }

    public Agency findByAgencyCode(String agencyCode) {
        return repository.findByAgencyCode(agencyCode);
    }

    public List<Agency> findByAgencyCodes(List<String> agencyCodes) {
        return repository.findByAgencyCodes(agencyCodes);
    }

    @Inject
    com.example.repository.AgentRepository agentRepository;

    @Transactional
    public Agency create(Agency agency) {
        if (agency.agentCodes == null) {
            agency.agentCodes = new ArrayList<>();
        }
        repository.persist(agency);
        return agency;
    }

    @Transactional
    public Agency update(String agencyCode, Agency agency) {
        Agency existing = repository.findByAgencyCode(agencyCode);
        if (existing == null) {
            return null;
        }
        existing.name = agency.name;
        existing.email = agency.email;
        existing.status = agency.status;
        if (agency.agentCodes != null) {
            if (existing.agentCodes == null) {
                existing.agentCodes = new ArrayList<>();
            } else {
                existing.agentCodes.clear();
            }
            existing.agentCodes.addAll(agency.agentCodes);
        }
        return existing;
    }

    public List<com.example.Agent> findAgentsByAgencyCode(String agencyCode) {
        Agency agency = findByAgencyCode(agencyCode);
        if (agency == null) {
            return null;
        }
        return agentRepository.findByAgentCodes(agency.agentCodes);
    }

    @Transactional
    public boolean delete(Long id) {
        return repository.deleteById(id);
    }
}
