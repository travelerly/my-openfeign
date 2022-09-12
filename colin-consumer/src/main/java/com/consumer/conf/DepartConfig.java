package com.consumer.conf;

import com.alibaba.cloud.nacos.ribbon.NacosRule;
import com.netflix.loadbalancer.IRule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author colin
 * @create 2022-06-25 16:31
 */
@Configuration
public class DepartConfig {

    // 指定负载均衡策略
    @Bean
    public IRule loadBalancer(){
        /*return new RandomRule();*/
        return new NacosRule();
    }
}
