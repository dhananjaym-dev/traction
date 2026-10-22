package dev.traction.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The demo service: governed Acme airports ({@code governed}), seed endpoints that dispatch demo
 * traffic ({@code seed}), and the Traction control plane (admin API and dashboard at
 * {@code /traction}).
 */
@SpringBootApplication
public class DemoApp {

    public static void main(String[] args) {
        SpringApplication.run(DemoApp.class, args);
    }
}
