package com.example.repository;

import com.example.Agency;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import org.hibernate.KeyType;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class AgencyRepository implements PanacheRepository<Agency> {

    public Agency findByAgencyCode(String agencyCode) {
        return getSession().find(Agency.class, agencyCode, KeyType.NATURAL);
    }

    public List<Agency> findByAgencyCodes(List<String> agencyCodes) {
        if (agencyCodes == null || agencyCodes.isEmpty()) {
            return List.of();
        }
        List<Agency> result = new ArrayList<>(agencyCodes.size());
        for (String agencyCode : agencyCodes) {
            Agency agency = getSession().find(Agency.class, agencyCode, KeyType.NATURAL);
            if (agency != null) {
                result.add(agency);
            }
        }
        return result;
    }
}
