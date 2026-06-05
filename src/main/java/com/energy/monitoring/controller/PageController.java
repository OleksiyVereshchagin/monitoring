package com.energy.monitoring.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {

    @GetMapping("/")
    public String home() {
        return "index";
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @GetMapping("/register")
    public String register() {
        return "register";
    }

    @GetMapping("/dashboard")
    public String dashboard() {
        return "dashboard";
    }

    @GetMapping("/devices")
    public String devices() {
        return "devices";
    }

    @GetMapping("/profile")
    public String profile() {
        return "profile";
    }

    @GetMapping("/anomalies")
    public String anomalies() {
        return "anomalies";
    }

    @GetMapping("/simulation")
    public String simulation() {
        return "simulation";
    }
}
