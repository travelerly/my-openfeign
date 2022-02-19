package com.consumer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * @author colin
 * @create 2022-02-18 12:54
 */
@EnableFeignClients
@SpringBootApplication
public class ConsumerFeign {

	public static void main(String[] args) {
		SpringApplication.run(ConsumerFeign.class,args);
	}
}
