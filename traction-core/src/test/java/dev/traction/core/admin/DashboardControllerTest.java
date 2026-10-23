package dev.traction.core.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

class DashboardControllerTest {

  @Test
  void servesTheSinglePageAsHtmlAndItNeverMutates() throws Exception {
    MockHttpServletResponse response = standaloneSetup(new DashboardController()).build()
        .perform(get("/traction")).andReturn().getResponse();

    assertEquals(200, response.getStatus());
    assertTrue(response.getContentType().startsWith("text/html"));
    String html = response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    assertTrue(html.contains("/airports") && html.contains("/charters"));
    assertFalse(html.contains("POST") || html.contains("<button"), "the dashboard is read-only");
    assertEquals(200, standaloneSetup(new DashboardController()).build()
        .perform(get("/traction/")).andReturn().getResponse().getStatus()); // the typed trailing slash
  }
}
