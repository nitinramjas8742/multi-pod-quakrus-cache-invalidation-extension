package com.example.resource;

import com.example.Agency;
import com.example.service.AgencyService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

@Path("/api/agencies")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AgencyResource {

    @Inject
    AgencyService service;

    @GET
    public List<Agency> list() {
        return service.findAll();
    }

    @GET
    @Path("/codes")
    public List<Agency> getByCodes(@QueryParam("codes") List<String> codes) {
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
        return service.findByAgencyCodes(resolved);
    }

    @GET
    @Path("/code/{agencyCode}")
    public Response getByCode(@PathParam("agencyCode") String agencyCode) {
        Agency agency = service.findByAgencyCode(agencyCode);
        if (agency == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(agency).build();
    }

    @GET
    @Path("/{id}")
    public Response get(@PathParam("id") Long id) {
        Agency agency = service.findById(id);
        if (agency == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(agency).build();
    }

    @POST
    public Response create(Agency agency) {
        Agency created = service.create(agency);
        return Response.created(URI.create("/api/agencies/" + created.id)).entity(created).build();
    }

    @PUT
    @Path("/code/{agencyCode}")
    public Response update(@PathParam("agencyCode") String agencyCode, Agency agency) {
        Agency updated = service.update(agencyCode, agency);
        if (updated == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(updated).build();
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") Long id) {
        if (!service.delete(id)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.noContent().build();
    }

    @GET
    @Path("/code/{agencyCode}/agents")
    public Response getAgentsByAgencyCode(@PathParam("agencyCode") String agencyCode) {
        List<com.example.Agent> agents = service.findAgentsByAgencyCode(agencyCode);
        if (agents == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(agents).build();
    }
}
