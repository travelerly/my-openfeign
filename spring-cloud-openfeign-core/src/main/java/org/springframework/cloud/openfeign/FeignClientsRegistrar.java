/*
 * Copyright 2013-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.openfeign;

import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionReaderUtils;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.ClassMetadata;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.filter.AbstractClassTestingTypeFilter;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.TypeFilter;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.*;

/**
 * @author Spencer Gibb
 * @author Jakub Narloch
 * @author Venil Noronha
 * @author Gang Li
 */
class FeignClientsRegistrar
		implements ImportBeanDefinitionRegistrar, ResourceLoaderAware, EnvironmentAware {

	// patterned after Spring Integration IntegrationComponentScanRegistrar
	// and RibbonClientsConfigurationRegistgrar

	private ResourceLoader resourceLoader;

	private Environment environment;

	FeignClientsRegistrar() {
	}

	static void validateFallback(final Class clazz) {
		Assert.isTrue(!clazz.isInterface(),
				"Fallback class must implement the interface annotated by @FeignClient");
	}

	static void validateFallbackFactory(final Class clazz) {
		Assert.isTrue(!clazz.isInterface(), "Fallback factory must produce instances "
				+ "of fallback classes that implement the interface annotated by @FeignClient");
	}

	static String getName(String name) {
		if (!StringUtils.hasText(name)) {
			return "";
		}

		String host = null;
		try {
			String url;
			if (!name.startsWith("http://") && !name.startsWith("https://")) {
				url = "http://" + name;
			}
			else {
				url = name;
			}
			host = new URI(url).getHost();

		}
		catch (URISyntaxException e) {
		}
		Assert.state(host != null, "Service id not legal hostname (" + name + ")");
		return name;
	}

	static String getUrl(String url) {
		if (StringUtils.hasText(url) && !(url.startsWith("#{") && url.contains("}"))) {
			if (!url.contains("://")) {
				url = "http://" + url;
			}
			try {
				new URL(url);
			}
			catch (MalformedURLException e) {
				throw new IllegalArgumentException(url + " is malformed", e);
			}
		}
		return url;
	}

	static String getPath(String path) {
		if (StringUtils.hasText(path)) {
			path = path.trim();
			if (!path.startsWith("/")) {
				path = "/" + path;
			}
			if (path.endsWith("/")) {
				path = path.substring(0, path.length() - 1);
			}
		}
		return path;
	}

	@Override
	public void setResourceLoader(ResourceLoader resourceLoader) {
		this.resourceLoader = resourceLoader;
	}

	/**
	 * FeignClient 完成配置注册
	 * @param metadata : 包含 @EnableFeignClients 与 @SpringBootApplication 两个注解的元数据
	 * @param registry
	 */
	@Override
	public void registerBeanDefinitions(AnnotationMetadata metadata,
			BeanDefinitionRegistry registry) {

		// 将 @EnableFeignClients 注解中的 defaultConfiguration 属性注册到 spring 的 beanDefinitionMap 中。
		registerDefaultConfiguration(metadata, registry);

		/**
		 * 1、扫描所有标注了 @FeignClient 注解的接口，即扫描所有 Feign 接口
		 * 2、将每个 @FeignClient 注解的 configuration 属性注册到 spring 的 beanDefinitionMap 中
		 * 3、根据 @FeignClient 注解元数据生成 FeignClientFactoryBean 的 BeanDefinition，并将其注册到 spring 的 beanDefinitionMap 中
		 */
		registerFeignClients(metadata, registry);
	}

	private void registerDefaultConfiguration(AnnotationMetadata metadata,
			BeanDefinitionRegistry registry) {

		/**
		 * 获取到 @EnableFeignClients 注解的属性值。
		 * 第二个参数为 true：表示将 @EnableFeignClients 注解的属性中类型为 Class 的属性转变为 String 类型，
		 * 防止这些数据先被加载为类
		 */
		Map<String, Object> defaultAttrs = metadata
				.getAnnotationAttributes(EnableFeignClients.class.getName(), true);

		if (defaultAttrs != null && defaultAttrs.containsKey("defaultConfiguration")) {
			String name;
			// 判断被当前注解标注的类是否为闭合类
			if (metadata.hasEnclosingClass()) {
				name = "default." + metadata.getEnclosingClassName();
			}
			else {
				// default + 当前启动类的全限定性类名，例如：default.com.consumer.ConsumerFeign
				name = "default." + metadata.getClassName();
			}

			// 注册这个 defaultConfiguration 属性
			registerClientConfiguration(registry, name,
					defaultAttrs.get("defaultConfiguration"));
		}
	}

	public void registerFeignClients(AnnotationMetadata metadata,
			BeanDefinitionRegistry registry) {

		// 定义一个扫描器
		ClassPathScanningCandidateComponentProvider scanner = getScanner();
		// 初始化扫描器
		scanner.setResourceLoader(this.resourceLoader);

		Set<String> basePackages;
		// 获取 @EnableFeignClients 注解的属性元数据
		Map<String, Object> attrs = metadata
				.getAnnotationAttributes(EnableFeignClients.class.getName());
		// 定义一个扫描标注了 @FeignClient 注解的接口的过滤器
		AnnotationTypeFilter annotationTypeFilter = new AnnotationTypeFilter(
				FeignClient.class);
		// 获取 "clients" 属性值
		final Class<?>[] clients = attrs == null ? null
				: (Class<?>[]) attrs.get("clients");
		if (clients == null || clients.length == 0) {
			// 若 "clients" 属性为空，则启动类路径扫描
			// 为扫描器添加一个过滤器（上面已经定义了）
			scanner.addIncludeFilter(annotationTypeFilter);
			// 获取 @EnableFeignClients 注解中所有指定的基本包
			basePackages = getBasePackages(metadata);
		}
		else { // 指定了 "clients" 属性值
			final Set<String> clientClasses = new HashSet<>();
			basePackages = new HashSet<>();
			for (Class<?> clazz : clients) {
				basePackages.add(ClassUtils.getPackageName(clazz));
				clientClasses.add(clazz.getCanonicalName());
			}
			AbstractClassTestingTypeFilter filter = new AbstractClassTestingTypeFilter() {
				@Override
				protected boolean match(ClassMetadata metadata) {
					String cleaned = metadata.getClassName().replaceAll("\\$", ".");
					return clientClasses.contains(cleaned);
				}
			};
			scanner.addIncludeFilter(
					new AllTypeFilter(Arrays.asList(filter, annotationTypeFilter)));
		}

		// 遍历 @EnableFeignClients 注解中所有指定的基本包
		for (String basePackage : basePackages) {
			// 扫描并获得 "候选组件" 集合（所有 Feign 接口都在其中）
			Set<BeanDefinition> candidateComponents = scanner
					.findCandidateComponents(basePackage);
			// 遍历 "候选组件" 集合
			for (BeanDefinition candidateComponent : candidateComponents) {
				// 只处理 Feign 接口组件
				if (candidateComponent instanceof AnnotatedBeanDefinition) {
					// verify annotated class is an interface
					AnnotatedBeanDefinition beanDefinition = (AnnotatedBeanDefinition) candidateComponent;
					// 从 beanDefinition 中获取注解元数据
					AnnotationMetadata annotationMetadata = beanDefinition.getMetadata();
					// 断言：若不是接口，则直接抛异常
					Assert.isTrue(annotationMetadata.isInterface(),
							"@FeignClient can only be specified on an interface");

					// 获取 @FeignClient 注解的属性
					Map<String, Object> attributes = annotationMetadata
							.getAnnotationAttributes(
									FeignClient.class.getCanonicalName());

					// 获取 Feign 名称
					String name = getClientName(attributes);
					// 将当前遍历到的 Feign 接口的 "configuration" 属性注册到缓存 map 中（注册到 spring 的 beanDefinitionMap 中）
					registerClientConfiguration(registry, name,
							attributes.get("configuration"));
					// 将 FeignClientFactoryBean 的 BeanDefinition 注册到缓存 map 中（注册到 spring 的 beanDefinitionMap 中）
					registerFeignClient(registry, annotationMetadata, attributes);
				}	// end-if
			}	// end-for
		}
	}

	/**
	 * 将 FeignClientFactoryBean 的 BeanDefinition 注册到缓存 map 中
	 * @param registry
	 * @param annotationMetadata
	 * @param attributes
	 */
	private void registerFeignClient(BeanDefinitionRegistry registry,
			AnnotationMetadata annotationMetadata, Map<String, Object> attributes) {
		String className = annotationMetadata.getClassName();
		BeanDefinitionBuilder definition = BeanDefinitionBuilder
				.genericBeanDefinition(FeignClientFactoryBean.class);
		validate(attributes);
		definition.addPropertyValue("url", getUrl(attributes));
		definition.addPropertyValue("path", getPath(attributes));
		String name = getName(attributes);
		definition.addPropertyValue("name", name);
		String contextId = getContextId(attributes);
		definition.addPropertyValue("contextId", contextId);
		definition.addPropertyValue("type", className);
		definition.addPropertyValue("decode404", attributes.get("decode404"));
		definition.addPropertyValue("fallback", attributes.get("fallback"));
		definition.addPropertyValue("fallbackFactory", attributes.get("fallbackFactory"));
		definition.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_BY_TYPE);

		String alias = contextId + "FeignClient";
		// 获取 FeignClientFactoryBean 的 BeanDefinition（Bean 定义信息）
		AbstractBeanDefinition beanDefinition = definition.getBeanDefinition();
		beanDefinition.setAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE, className);

		// has a default, won't be null
		boolean primary = (Boolean) attributes.get("primary");

		beanDefinition.setPrimary(primary);

		String qualifier = getQualifier(attributes);
		if (StringUtils.hasText(qualifier)) {
			alias = qualifier;
		}
		// 生成 BeanDefinition 的 holder（持有者），通过这个 holder 可以获取到这个 BeanDefinition
		BeanDefinitionHolder holder = new BeanDefinitionHolder(beanDefinition, className,
				new String[] { alias });
		// 注册到 spring 的 beanDefinitionMap 中
		BeanDefinitionReaderUtils.registerBeanDefinition(holder, registry);
	}

	private void validate(Map<String, Object> attributes) {
		AnnotationAttributes annotation = AnnotationAttributes.fromMap(attributes);
		// This blows up if an aliased property is overspecified
		// FIXME annotation.getAliasedString("name", FeignClient.class, null);
		validateFallback(annotation.getClass("fallback"));
		validateFallbackFactory(annotation.getClass("fallbackFactory"));
	}

	/* for testing */ String getName(Map<String, Object> attributes) {
		String name = (String) attributes.get("serviceId");
		if (!StringUtils.hasText(name)) {
			name = (String) attributes.get("name");
		}
		if (!StringUtils.hasText(name)) {
			name = (String) attributes.get("value");
		}
		name = resolve(name);
		return getName(name);
	}

	private String getContextId(Map<String, Object> attributes) {
		String contextId = (String) attributes.get("contextId");
		if (!StringUtils.hasText(contextId)) {
			return getName(attributes);
		}

		contextId = resolve(contextId);
		return getName(contextId);
	}

	private String resolve(String value) {
		if (StringUtils.hasText(value)) {
			return this.environment.resolvePlaceholders(value);
		}
		return value;
	}

	private String getUrl(Map<String, Object> attributes) {
		String url = resolve((String) attributes.get("url"));
		return getUrl(url);
	}

	private String getPath(Map<String, Object> attributes) {
		String path = resolve((String) attributes.get("path"));
		return getPath(path);
	}

	protected ClassPathScanningCandidateComponentProvider getScanner() {
		return new ClassPathScanningCandidateComponentProvider(false, this.environment) {
			@Override
			protected boolean isCandidateComponent(
					AnnotatedBeanDefinition beanDefinition) {
				boolean isCandidate = false;
				if (beanDefinition.getMetadata().isIndependent()) {
					if (!beanDefinition.getMetadata().isAnnotation()) {
						isCandidate = true;
					}
				}
				return isCandidate;
			}
		};
	}

	/**
	 * 获取 @EnableFeignClients 注解中所有指定的基本包
	 * @param importingClassMetadata
	 * @return
	 */
	protected Set<String> getBasePackages(AnnotationMetadata importingClassMetadata) {
		// 获取 @EnableFeignClients 注解中所有指定的基本包
		Map<String, Object> attributes = importingClassMetadata
				.getAnnotationAttributes(EnableFeignClients.class.getCanonicalName());

		Set<String> basePackages = new HashSet<>();
		// 遍历 "value" 属性，并保存非空数据
		for (String pkg : (String[]) attributes.get("value")) {
			if (StringUtils.hasText(pkg)) {
				basePackages.add(pkg);
			}
		}

		// 遍历 "basePackages" 属性，并保存非空数据
		for (String pkg : (String[]) attributes.get("basePackages")) {
			if (StringUtils.hasText(pkg)) {
				basePackages.add(pkg);
			}
		}

		// 遍历 "basePackageClasses" 属性，并保存非空数据
		for (Class<?> clazz : (Class[]) attributes.get("basePackageClasses")) {
			basePackages.add(ClassUtils.getPackageName(clazz));
		}

		// 若没有指定以上三个属性，则将当前注解所标注的类所在的包添加到 basePackages 中
		if (basePackages.isEmpty()) {
			basePackages.add(
					ClassUtils.getPackageName(importingClassMetadata.getClassName()));
		}
		return basePackages;
	}

	private String getQualifier(Map<String, Object> client) {
		if (client == null) {
			return null;
		}
		String qualifier = (String) client.get("qualifier");
		if (StringUtils.hasText(qualifier)) {
			return qualifier;
		}
		return null;
	}

	private String getClientName(Map<String, Object> client) {
		if (client == null) {
			return null;
		}

		// 属性值优先级：contextId > value > name > serviceId

		// 获取 "contextId" 属性
		String value = (String) client.get("contextId");
		// 若 "contextId" 属性值为空，则获取 "value" 属性值
		if (!StringUtils.hasText(value)) {
			value = (String) client.get("value");
		}
		// 若 "value" 属性值为空，则获取 "name" 属性值
		if (!StringUtils.hasText(value)) {
			value = (String) client.get("name");
		}
		// 若 "name" 属性值为空，则获取 "serviceId" 属性值。
		if (!StringUtils.hasText(value)) {
			value = (String) client.get("serviceId");
		}
		if (StringUtils.hasText(value)) {
			return value;
		}

		throw new IllegalStateException("Either 'name' or 'value' must be provided in @"
				+ FeignClient.class.getSimpleName());
	}

	private void registerClientConfiguration(BeanDefinitionRegistry registry, Object name,
			Object configuration) {

		/**
		 * 生成一个 BeanDefinitionBuilder，其可以构建一个 BeanDefinition 实例
		 * 		而 BeanDefinition 实例可以生成一个 FeignClientSpecification 实例
		 * 		而 FeignClientSpecification 实例能够为 FeignClient 的生成提供生成规范
		 */
		BeanDefinitionBuilder builder = BeanDefinitionBuilder
				.genericBeanDefinition(FeignClientSpecification.class);
		builder.addConstructorArgValue(name);
		builder.addConstructorArgValue(configuration);

		// 注册 BeanDefinition 实例（builder.getBeanDefinition()：获取到构建的 BeanDefinition 实例）
		// 注册到 spring 的 beanDefinitionMap 中
		registry.registerBeanDefinition(
				name + "." + FeignClientSpecification.class.getSimpleName(),
				builder.getBeanDefinition());
	}

	@Override
	public void setEnvironment(Environment environment) {
		this.environment = environment;
	}

	/**
	 * Helper class to create a {@link TypeFilter} that matches if all the delegates
	 * match.
	 *
	 * @author Oliver Gierke
	 */
	private static class AllTypeFilter implements TypeFilter {

		private final List<TypeFilter> delegates;

		/**
		 * Creates a new {@link AllTypeFilter} to match if all the given delegates match.
		 * @param delegates must not be {@literal null}.
		 */
		AllTypeFilter(List<TypeFilter> delegates) {
			Assert.notNull(delegates, "This argument is required, it must not be null");
			this.delegates = delegates;
		}

		@Override
		public boolean match(MetadataReader metadataReader,
				MetadataReaderFactory metadataReaderFactory) throws IOException {

			for (TypeFilter filter : this.delegates) {
				if (!filter.match(metadataReader, metadataReaderFactory)) {
					return false;
				}
			}

			return true;
		}

	}

}
