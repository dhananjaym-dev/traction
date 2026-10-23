package dev.traction.core.admin;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the read-only dashboard: one static HTML file that polls the admin API's GET endpoints.
 * No build step, no external assets.
 */
@RestController
public class DashboardController {

  private static final String PAGE = "traction/dashboard.html";

  private final String html;

  public DashboardController() {
    try (var in = new ClassPathResource(PAGE).getInputStream()) {
      this.html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("dashboard page " + PAGE + " is missing", e);
    }
  }

  @GetMapping({"/traction", "/traction/"})
  public ResponseEntity<String> dashboard() {
    return ResponseEntity.ok()
        .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
        .body(html);
  }
}
