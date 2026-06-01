package com.energy.monitoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class TelemetryControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void telemetryEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/devices"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/readings"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/households"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void authenticatedUserCanManageHouseholdDevicesAndReadings() throws Exception {
        String token = registerAndGetToken("telemetry-" + UUID.randomUUID());
        Long householdId = createHousehold(token, "My Apartment", "APARTMENT");
        Long deviceId = createDevice(token, householdId, "Холодильник", "FRIDGE");

        mockMvc.perform(post("/api/readings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "deviceId": %d,
                                  "timestamp": "2026-05-23T14:00:00",
                                  "powerConsumption": 0.42,
                                  "voltage": 220.0,
                                  "current": 1.91,
                                  "source": "sensor",
                                  "sessionId": "demo-session-1"
                                }
                                """.formatted(deviceId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deviceId").value(deviceId))
                .andExpect(jsonPath("$.deviceName").value("Холодильник"))
                .andExpect(jsonPath("$.powerConsumption").value(0.42))
                .andExpect(jsonPath("$.voltage").value(220.0))
                .andExpect(jsonPath("$.current").value(1.91))
                .andExpect(jsonPath("$.source").value("SENSOR"))
                .andExpect(jsonPath("$.sessionId").value("demo-session-1"));

        mockMvc.perform(get("/api/devices")
                        .header("Authorization", "Bearer " + token)
                        .param("householdId", householdId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].householdId").value(householdId))
                .andExpect(jsonPath("$[0].householdName").value("My Apartment"));

        mockMvc.perform(get("/api/readings")
                        .header("Authorization", "Bearer " + token)
                        .param("deviceId", deviceId.toString())
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].deviceId").value(deviceId))
                .andExpect(jsonPath("$.content[0].voltage").value(220.0))
                .andExpect(jsonPath("$.content[0].current").value(1.91))
                .andExpect(jsonPath("$.content[0].source").value("SENSOR"));
    }

    @Test
    void readingsRejectInvalidValuesAndForeignDevices() throws Exception {
        String firstToken = registerAndGetToken("owner-" + UUID.randomUUID());
        String secondToken = registerAndGetToken("reader-" + UUID.randomUUID());
        Long householdId = createHousehold(firstToken, "House", "HOUSE");
        Long foreignDeviceId = createDevice(firstToken, householdId, "Освітлення", "LIGHT");

        mockMvc.perform(post("/api/readings")
                        .header("Authorization", "Bearer " + firstToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "deviceId": %d,
                                  "powerConsumption": -0.1
                                }
                                """.formatted(foreignDeviceId)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/readings")
                        .header("Authorization", "Bearer " + secondToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "deviceId": %d,
                                  "powerConsumption": 0.25
                                }
                                """.formatted(foreignDeviceId)))
                .andExpect(status().isNotFound());
    }

    @Test
    void modelStatusReturnsNotTrainedForNewUser() throws Exception {
        String token = registerAndGetToken("ml-status-" + UUID.randomUUID());

        mockMvc.perform(get("/api/ml/model-status")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_TRAINED"))
                .andExpect(jsonPath("$.modelReady").value(false));
    }

    private String registerAndGetToken(String username) throws Exception {
        String response = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "%s",
                                  "email": "%s@example.com",
                                  "password": "password123"
                                }
                                """.formatted(username, username)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode json = objectMapper.readTree(response);
        return json.get("token").asText();
    }

    private Long createHousehold(String token, String name, String type) throws Exception {
        String response = mockMvc.perform(post("/households")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "%s",
                                  "type": "%s"
                                }
                                """.formatted(name, type)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.type").value(type))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode json = objectMapper.readTree(response);
        return json.get("id").asLong();
    }

    private Long createDevice(String token, Long householdId, String name, String type) throws Exception {
        String response = mockMvc.perform(post("/devices")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "%s",
                                  "householdId": %d,
                                  "type": "%s",
                                  "behaviorProfile": "CYCLIC",
                                  "nominalPower": 1.50,
                                  "active": true
                                }
                                """.formatted(name, householdId, type)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.householdId").value(householdId))
                .andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.type").value(type))
                .andExpect(jsonPath("$.behaviorProfile").value("CYCLIC"))
                .andExpect(jsonPath("$.nominalPower").value(1.50))
                .andExpect(jsonPath("$.active").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode json = objectMapper.readTree(response);
        return json.get("id").asLong();
    }
}
