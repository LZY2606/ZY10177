package com.example.meltlineage;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:sqlite:target/test-web.db",
        "spring.datasource.hikari.maximum-pool-size=1"
})
class WebSmokeTest {
    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;

    @Test
    void indexPageShowsTitle() {
        String body = rest.getForObject("http://127.0.0.1:" + port + "/", String.class);
        assertNotNull(body);
        assertTrue(body.contains("熔痕谱系"));
    }

    @Test
    void importFixtureAndReadState() {
        String r = rest.postForObject("http://127.0.0.1:" + port + "/api/import-fixture", null, String.class);
        assertNotNull(r);
        assertTrue(r.contains("version"));
        String state = rest.getForObject("http://127.0.0.1:" + port + "/api/state", String.class);
        assertTrue(state.contains("\"imported\":true"));
        assertTrue(state.contains("trackStats"));
    }
}
