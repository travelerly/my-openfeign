package com.provider.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequestMapping("/provider/depart")
@RestController
public class DepartController {

	@Value("${server.port}")
	public String port;

    @GetMapping("/getPort")
    public String getHandle() {
        return "server.port: " + port;
    }
}
