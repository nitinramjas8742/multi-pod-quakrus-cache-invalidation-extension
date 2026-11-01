package com.example.resource;

import com.example.Agent;
import com.example.service.AgentService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.List;

@Path("/api/agents")
@Produces(MediaType.APPLICATION_JSON)
public class AgentResource {

    @Inject
    AgentService service;

    @GET
    @Path("/code/{agentCode}")
    public Response getByCode(@PathParam("agentCode") String agentCode) {
        Agent agent = service.findByAgentCode(agentCode);
        if (agent == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(agent).build();
    }

    @GET
    @Path("/codes")
    public List<Agent> getByCodes(@QueryParam("codes") List<String> codes) {
        List<String> resolved = new ArrayList<>();
        if (codes != null) {
            for (String code : codes) {
                for (String part : code.split(",")) {
                    if (!part.isBlank()) {
                        resolved.add(part.trim());
                    }
                }
            }
        }
        return service.findByAgentCodes(resolved);
    }
}
