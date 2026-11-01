package com.socialcoisa.rede.companies.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

@SpringBootTest
@AutoConfigureMockMvc
class MicroProjectEditTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void editingRemovingAndAuthorization() throws Exception {
        var auth = new TestAuthHelper(mvc, json);
        var owner = auth.authenticate("mpedit_owner");
        var editor = auth.authenticate("mpedit_editor");
        var outsider = auth.authenticate("mpedit_outsider");
        var recruiter = auth.authenticate("mpedit_recruiter");
        String name = "Empresa Editar " + UUID.randomUUID().toString().substring(0, 8);
        var company = mvc.perform(post("/empresas").header("X-Session-Token", owner.token())
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("name", name))))
            .andExpect(status().isOk()).andReturn();
        var data = json.readTree(company.getResponse().getContentAsString());
        String companyId = data.get("id").asText(), slug = data.get("slug").asText();
        for (var member : Map.of(editor.userId(), "EDITOR", recruiter.userId(), "RECRUITER").entrySet()) {
            mvc.perform(post("/empresas/" + companyId + "/members").header("X-Session-Token", owner.token())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("userId", member.getKey(), "role", member.getValue()))))
                .andExpect(status().isOk());
        }
        var fields = Map.of("title", "Microprojeto original", "problem", "Um problema suficientemente longo para o teste de projeto.",
            "deliverables", "Entrega original", "acceptanceCriteria", "Criterio original", "budget", "100");
        var created = mvc.perform(post("/empresas/" + companyId + "/microprojetos").header("X-Session-Token", owner.token())
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(fields)))
            .andExpect(status().isOk()).andReturn();
        String id = json.readTree(created.getResponse().getContentAsString()).get("id").asText();
        String path = "/microprojetos/" + id;
        var updated = Map.of("title", "Microprojeto corrigido", "problem", "Outro problema suficientemente longo para o teste de projeto.",
            "deliverables", "Entrega corrigida", "acceptanceCriteria", "Criterio corrigido", "budget", "200");
        for (String token : new String[] {outsider.token(), recruiter.token()}) {
            mvc.perform(put(path).header("X-Session-Token", token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(updated))).andExpect(status().isForbidden());
            mvc.perform(delete(path).header("X-Session-Token", token)).andExpect(status().isForbidden());
        }
        mvc.perform(put(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(updated)))
            .andExpect(status().isUnauthorized());
        mvc.perform(delete(path)).andExpect(status().isUnauthorized());
        mvc.perform(put(path).header("X-Session-Token", editor.token()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("title", "Curto")))).andExpect(status().isBadRequest());
        var tooLong = new java.util.HashMap<>(updated);
        tooLong.put("budget", "x".repeat(161));
        mvc.perform(put(path).header("X-Session-Token", editor.token()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(tooLong))).andExpect(status().isBadRequest());
        mvc.perform(put(path).header("X-Session-Token", editor.token()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(updated))).andExpect(status().isOk());
        mvc.perform(get(path)).andExpect(status().isOk()).andExpect(jsonPath("$.project.title").value("Microprojeto corrigido"))
            .andExpect(jsonPath("$.project.deliverables").value("Entrega corrigida"))
            .andExpect(jsonPath("$.project.problem").value("Outro problema suficientemente longo para o teste de projeto."))
            .andExpect(jsonPath("$.project.acceptanceCriteria").value("Criterio corrigido"))
            .andExpect(jsonPath("$.project.budget").value("200"));
        mvc.perform(delete(path).header("X-Session-Token", owner.token())).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(get(path)).andExpect(status().isNotFound());
        mvc.perform(get("/microprojetos?limit=100")).andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id == '" + id + "')]").isEmpty());
        mvc.perform(get("/empresa/" + slug)).andExpect(jsonPath("$.projects").isEmpty())
            .andExpect(jsonPath("$.stats.openProjects").value(0));
        mvc.perform(delete(path).header("X-Session-Token", owner.token())).andExpect(status().isConflict());
    }

    @Test
    void proposalsAndAcceptedWorkProtectTheContract() throws Exception {
        var auth = new TestAuthHelper(mvc, json);
        var owner = auth.authenticate("mpedit_flow_owner");
        var professional = auth.authenticate("mpedit_flow_pro");
        auth.activateProfessional(professional);
        var company = mvc.perform(post("/empresas").header("X-Session-Token", owner.token())
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("name", "Projeto Fluxo " + UUID.randomUUID().toString().substring(0, 8)))))
            .andExpect(status().isOk()).andReturn();
        String companyId = json.readTree(company.getResponse().getContentAsString()).get("id").asText();
        var fields = Map.of("title", "Projeto com candidato", "problem", "Um problema suficientemente longo para o teste de projeto.",
            "deliverables", "Entrega", "acceptanceCriteria", "Criterio");
        var result = mvc.perform(post("/empresas/" + companyId + "/microprojetos").header("X-Session-Token", owner.token())
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(fields))).andExpect(status().isOk()).andReturn();
        String path = "/microprojetos/" + json.readTree(result.getResponse().getContentAsString()).get("id").asText();
        var proposal = mvc.perform(post(path + "/proposals").header("X-Session-Token", professional.token())
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("message", "Uma proposta suficientemente longa para este projeto."))))
            .andExpect(status().isOk()).andReturn();
        String proposalId = json.readTree(proposal.getResponse().getContentAsString()).get("id").asText();
        for (int phase = 0; phase < 3; phase++) {
            mvc.perform(put(path).header("X-Session-Token", owner.token()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(fields))).andExpect(status().isConflict());
            mvc.perform(delete(path).header("X-Session-Token", owner.token())).andExpect(status().isConflict());
            if (phase == 0) mvc.perform(post(path + "/proposals/" + proposalId + "/select").header("X-Session-Token", owner.token())).andExpect(status().isOk());
            if (phase == 1) mvc.perform(post(path + "/complete").header("X-Session-Token", owner.token())).andExpect(status().isOk());
        }
    }
}
