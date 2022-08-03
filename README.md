# 声明式客户端 OpenFeign 与负载均衡

## 概述

### OpenFeign 简介

声明式 REST 客户端：Feign 通过使用 JAX-RS 或 SpringMVC 注解的装饰方式，生成接口的动态代理。



#### 综合说明

Feign，假的，伪装的

OpenFeign 可以将提供者提供的 Restful 服务伪装为接口进行消费，消费者只需使用 `feign 接口 + 注解` 的方式即可直接调用提供者提供的 Restful 服务，而无需再使用 RestTemplate。

**注意，OpenFeign 只与消费者有关，与提供者没有任何关系。**



### Ribbon 与 OpenFeign

OpenFeign 默认使用 Ribbon 作为负载均衡组件。 OpenFeign 直接内置了 Ribbon。即在导入 OpenFeign 依赖后，无需再专门导入 Ribbon 依赖了。

OpenFeign 也是运行在消费者端的，使用 Ribbon 进行负载均衡，所以 OpenFeign 直接内置了 Ribbon。即在导入 OpenFeign 依赖后，无需再专门导入 Ribbon 依赖了。

#### 消费者客户端技术选型

1. RestTemplate
2. OpenFeign
3. Dubbo Spring Cloud

---



## OpenFeign 源码解析

### 重要类与接口解析

#### @EnableFeignClients

在 SpringBoot 中存在大量的 @EnableXxx 这种注解。它们的作用是，开启某项功能。其实它们本质上是为了导入某个类来完成某项功能。所以这个注解一般会组合一个 @Import 注解用于导入类。导入的类一般有三种：

- **配置类**：一般以 Configuration 结尾，完成自动配置
- **选择器**：一般以 Selector 结尾，完成自动选择
- **注册器**：一般以 Registrar 结尾，完成自动注册



#### @FeignClient 接口

1. Feign 接口名无需与业务接口名称相同，叫什么都可以，但一般起名与业务接口名相同；
2. 方法签名要与业务接口的相同，但具体的方法名称不一定非要与业务方法名称相同，叫什么都可以，但一般起名与业务方法名相同；
3. 必须要有 @FeignClient 注解；
4. `@FeignClient(value = "colin-provider")`，vlaue 和 name 是同义词，该属性必须被指定，是可选协议前缀，会自动在微服务名称前面添加 `http://`。
5. qualifier 属性，在注入时，指定目标类的名称，也即 `byName`
6. url 属性，采用直连方式，不会负载均衡
7. path，方法级别的前缀映射，作用相当于 `@RequestMapping("/provider/depart")` 中配置的内容



#### FeignClientSpecification 类

FeignClientSpecification 是一个 FeignClient 的生成规范。

```java
class FeignClientSpecification implements NamedContextFactory.Specification {

	private String name;

	private Class<?>[] configuration;
    
    ……  
}
```



#### BeanDefinition 接口

BeanDefinition 是一个 Bean 定义器，描述了一个 bean 的定义信息，可以用于生成一个 bean 实例。

```JAVA
public interface BeanDefinition extends AttributeAccessor, BeanMetadataElement { …… }
```



#### BeanDefinitionRegistry 接口

BeanDefinitionRegistry 是一个 BeanDefinition 注册器。

```java
public interface BeanDefinitionRegistry extends AliasRegistry { …… }
```



#### FeignContext 类

FeignContext 是一个为 Feign Client 创建所准备的上下文对象。

```java
public class FeignContext extends NamedContextFactory<FeignClientSpecification> {

	public FeignContext() {
		super(FeignClientsConfiguration.class, "feign", "feign.client.name");
	}
    ……
}
```

其父类中包含两个很重要的集合：

```java
public abstract class NamedContextFactory<C extends NamedContextFactory.Specification> implements DisposableBean, ApplicationContextAware {
    
    // key 为 FeignClient 的名称（即所要调用的微服务名称），value 是组装这个 FeignClient 所必须的组件所在的 Spring 子容器
    private Map<String, AnnotationConfigApplicationContext> contexts = new ConcurrentHashMap();
    
    /**
     * 这个 map 中存放的是 @EnableFeignClients 与 @FeignClient 两个注解中的 configuration 属性值。 这个属性值只有两类：
     * 第一类只有一个，其 key 为字符串 "default + 当前启动类的全限定性类名" ，
     * 例如：default.com.abc.ConsumerFeign8080，value 为 @EnableFeignClients 的 defaultConfiguration 属性值
     * 第二类有多个，其 key 为当前 @FeignClient 的名称，value 为这个注解的 configuration 属性值
     */
    private Map<String, C> configurations = new ConcurrentHashMap();
    
    ……
}
```



### Feign Client 的创建

#### 完成配置注册

```java
/**
 * FeignClientsRegistrar：通过注解 @EnableFeignClients 导入（@Import）的
 * 工厂刷新时，在执行工厂增强方法 invokeBeanFactoryPostProcessors() 中会调用此方法进行注册
 */
public void registerBeanDefinitions(AnnotationMetadata metadata,
                                    BeanDefinitionRegistry registry) {

    // 将 @EnableFeignClients 注解中的 defaultConfiguration 属性注册到 spring 的 beanDefinitionMap 中。
    registerDefaultConfiguration(metadata, registry);

    /**
	 * 1、扫描所有标注了 @FeignClient 注解的接口，即扫描所有 Feign 接口
	 * 2、将每个 @FeignClient 注解的 configuration 属性注册到 spring 的 beanDefinitionMap 中
	 * 3、根据 @FeignClient 注解元数据生成 FeignClient 的 BeanDefinition，并注册到 beanDefinitionMap 中
	 *    FeignClient 的类型是 FeignClientFactoryBean，是一个工厂 Bean，其实例化时通过工厂 bean 的 getObject() 方法完成的
	 */
    registerFeignClients(metadata, registry);
}
```



#### 完成自动配置

```java
/**
 * spring-cloud-fegin-core 模块的 spring.factories 文件中的自动配置类 FeignAutoConfiguration
 * 会给容器中配置(@Bean) FeignContext。
 */
public FeignContext feignContext() {
	FeignContext context = new FeignContext();
	/**
	 * configurations 中存放的是 @EnableFeignClients 与 @FeignClient 两个注解中的 configuration 属性值。
	 * 这个属性值只有两类：
	 * 第一类只有一个，其 key 为字符串 default + 当前启动类的全限定性类名 ，
	 * 		例如：default.com.abc.ConsumerFeign8080，value 为 @EnableFeignClients 的 defaultConfiguration 属性值
	 * 第二类有多个，其 key 为当前 @FeignClient 的名称，value 为这个注解的 configuration 属性值
	 */
	context.setConfigurations(this.configurations);
	return context;
}
```



#### 生成 Feign Client

```java
/**
 * Feign Client 是一个 FeignClientFactoryBean 类型的工厂 Bean，其实例化是通过工厂 Bean 的 getObject() 方法完成的
 */
public Object getObject() throws Exception {
	return getTarget();
}

<T> T getTarget() {
	// 从 Spring 容器中获取到 FeignContext（即 FeignClient 对应的 Spring 子容器）
	FeignContext context = applicationContext.getBean(FeignContext.class);
	// 从 Spring 子容器中获取 Feign.Builder 实例
	Feign.Builder builder = feign(context);

	// 若 url 属性为空，则说明使用负载均衡方式调用提供者
	if (!StringUtils.hasText(url)) {
		if (!name.startsWith("http")) {
			url = "http://" + name;
		}
		else {
			url = name;
		}
		url += cleanPath();

		// 创建代理对象，最终由 ReflectiveFeign#newInstance() 创建出代理对象
		return (T) loadBalance(builder, context,
				new HardCodedTarget<>(type, name, url));
	}

	// url 属性不为空，则采用直连的方式访问提供者
	if (StringUtils.hasText(url) && !url.startsWith("http")) {
		url = "http://" + url;
	}
	String url = this.url + cleanPath();
	// 从 Spring 子容器中获取 Client（尚未初始化）
	Client client = getOptional(context, Client.class);
	if (client != null) {
		if (client instanceof LoadBalancerFeignClient) {
			// not load balancing because we have a url,
			// but ribbon is on the classpath, so unwrap
			client = ((LoadBalancerFeignClient) client).getDelegate();
		}
		if (client instanceof FeignBlockingLoadBalancerClient) {
			// not load balancing because we have a url,
			// but Spring Cloud LoadBalancer is on the classpath, so unwrap
			client = ((FeignBlockingLoadBalancerClient) client).getDelegate();
		}
		builder.client(client);
	}
	Targeter targeter = get(context, Targeter.class);
	return (T) targeter.target(this, builder, context,
			new HardCodedTarget<>(type, name, url));
}
```



### 发出网络请求

```java
// 发起请求后，代理类介入增强
ReflectiveFeign.invoke(Object proxy, Method method, Object[] args);
```



### Ribbon 负载均衡

```java
LoadBalancerFeignClient.execute(Request request, Request.Options options);
```



### Spring Cloud LoadBalancer 负载均衡

```java
FeignBlockingLoadBalancerClient.execute(Request request, Request.Options options);
```

---

## OpenFeign 原理流程图

<img src="src/doc/OpenFeign 原理流程.jpg" alt="OpenFeign 原理流程"  />

---

## Ribbon 内置负载均衡算法

### RoundRobinRule

轮询策略。Ribbon 默认采用的策略。若经过一轮轮询没有找到可用的 provider，其最多轮询 10 次（代码中写死的，不能修改）。若还未找到，则返回 null。

```java
// 轮询算法
private AtomicInteger nextServerCyclicCounter;

private int incrementAndGetModulo(int modulo) {
    for (;;) {
        int current = nextServerCyclicCounter.get();
        int next = (current + 1) % modulo;
        if (nextServerCyclicCounter.compareAndSet(current, next))
            return next;
    }
}
```



### RandomRule

随机策略，从所有可用的 provider 中随机选择一个。

```java
// 随机算法
protected int chooseRandomInt(int serverCount) {
    return ThreadLocalRandom.current().nextInt(serverCount);
}
```



### RetryRule

重试策略。先按照轮询算法获取 provider，若获取失败，则在指定的时限内重 试。默认的时限为 500 毫秒。



### BestAvailableRule

最可用策略。选择并发量最小的 provider，即连接的消费者数量最少的 provider。其会遍历服务列表中的每一个 provider，选择当前连接数量 minimalConcurrentConnections 最小 的 provider。



### AvailabilityFilteringRule

可用过滤算法。该算法规则是：过滤掉处于熔断状态的 provider 与已经超过连接极限的 provider，对剩余 provider 采用轮询策略。



