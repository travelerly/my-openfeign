package com.consumer.controller;

import com.consumer.service.DepartService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/consumer/depart")
public class DepartController {

    //  Feign接口
    @Autowired
    private DepartService service;

	@GetMapping("/getPort")
	public String getPort(){
		return service.getPort();
	}

}
