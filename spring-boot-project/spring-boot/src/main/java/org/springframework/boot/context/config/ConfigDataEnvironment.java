/*
 * Copyright 2012-2022 the original author or authors.
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

package org.springframework.boot.context.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.logging.Log;

import org.springframework.boot.BootstrapRegistry.InstanceSupplier;
import org.springframework.boot.BootstrapRegistry.Scope;
import org.springframework.boot.ConfigurableBootstrapContext;
import org.springframework.boot.DefaultPropertiesPropertySource;
import org.springframework.boot.context.config.ConfigDataEnvironmentContributors.BinderOption;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.log.LogMessage;
import org.springframework.util.StringUtils;

/**
 * Wrapper around a {@link ConfigurableEnvironment} that can be used to import and apply
 * {@link ConfigData}. Configures the initial set of
 * {@link ConfigDataEnvironmentContributors} by wrapping property sources from the Spring
 * {@link Environment} and adding the initial set of locations.
 * <p>
 * The initial locations can be influenced via the {@link #LOCATION_PROPERTY},
 * {@value #ADDITIONAL_LOCATION_PROPERTY} and {@value #IMPORT_PROPERTY} properties. If no
 * explicit properties are set, the {@link #DEFAULT_SEARCH_LOCATIONS} will be used.
 *
 * @author Phillip Webb
 * @author Madhura Bhave
 */
class ConfigDataEnvironment {

	/**
	 * Property used override the imported locations.
	 */
	static final String LOCATION_PROPERTY = "spring.config.location";

	/**
	 * Property used to provide additional locations to import.
	 */
	static final String ADDITIONAL_LOCATION_PROPERTY = "spring.config.additional-location";

	/**
	 * Property used to provide additional locations to import.
	 */
	static final String IMPORT_PROPERTY = "spring.config.import";

	/**
	 * Property used to determine what action to take when a
	 * {@code ConfigDataNotFoundAction} is thrown.
	 * @see ConfigDataNotFoundAction
	 */
	static final String ON_NOT_FOUND_PROPERTY = "spring.config.on-not-found";

	/**
	 * Default search locations used if not {@link #LOCATION_PROPERTY} is found.
	 */
	static final ConfigDataLocation[] DEFAULT_SEARCH_LOCATIONS;
	static {
		List<ConfigDataLocation> locations = new ArrayList<>();
		locations.add(ConfigDataLocation.of("optional:classpath:/;optional:classpath:/config/"));
		locations.add(ConfigDataLocation.of("optional:file:./;optional:file:./config/;optional:file:./config/*/"));
		DEFAULT_SEARCH_LOCATIONS = locations.toArray(new ConfigDataLocation[0]);
	}

	private static final ConfigDataLocation[] EMPTY_LOCATIONS = new ConfigDataLocation[0];

	private static final Bindable<ConfigDataLocation[]> CONFIG_DATA_LOCATION_ARRAY = Bindable
			.of(ConfigDataLocation[].class);

	private static final Bindable<List<String>> STRING_LIST = Bindable.listOf(String.class);

	private static final BinderOption[] ALLOW_INACTIVE_BINDING = {};

	private static final BinderOption[] DENY_INACTIVE_BINDING = { BinderOption.FAIL_ON_BIND_TO_INACTIVE_SOURCE };

	private final DeferredLogFactory logFactory;

	private final Log logger;

	private final ConfigDataNotFoundAction notFoundAction;

	private final ConfigurableBootstrapContext bootstrapContext;

	private final ConfigurableEnvironment environment;

	private final ConfigDataLocationResolvers resolvers;

	private final Collection<String> additionalProfiles;

	private final ConfigDataEnvironmentUpdateListener environmentUpdateListener;

	private final ConfigDataLoaders loaders;

	private final ConfigDataEnvironmentContributors contributors;

	/**
	 * Create a new {@link ConfigDataEnvironment} instance.
	 * @param logFactory the deferred log factory
	 * @param bootstrapContext the bootstrap context
	 * @param environment the Spring {@link Environment}.
	 * @param resourceLoader {@link ResourceLoader} to load resource locations
	 * @param additionalProfiles any additional profiles to activate
	 * @param environmentUpdateListener optional
	 * {@link ConfigDataEnvironmentUpdateListener} that can be used to track
	 * {@link Environment} updates.
	 */
	ConfigDataEnvironment(DeferredLogFactory logFactory, ConfigurableBootstrapContext bootstrapContext,
			ConfigurableEnvironment environment, ResourceLoader resourceLoader, Collection<String> additionalProfiles,
			ConfigDataEnvironmentUpdateListener environmentUpdateListener) {
		Binder binder = Binder.get(environment);
		UseLegacyConfigProcessingException.throwIfRequested(binder);
		this.logFactory = logFactory;
		this.logger = logFactory.getLog(getClass());
		// 可以通过spring.config.on-not-found来指定某个配置文件没有找到时的动作，默认是抛异常
		this.notFoundAction = binder.bind(ON_NOT_FOUND_PROPERTY, ConfigDataNotFoundAction.class)
				.orElse(ConfigDataNotFoundAction.FAIL);
		this.bootstrapContext = bootstrapContext;
		this.environment = environment;

		// 从spring.factories中拿到ConfigDataLocationResolver，默认会有两个：
		// ConfigTreeConfigDataLocationResolver和StandardConfigDataLocationResolver
		this.resolvers = createConfigDataLocationResolvers(logFactory, bootstrapContext, binder, resourceLoader);

		this.additionalProfiles = additionalProfiles;
		this.environmentUpdateListener = (environmentUpdateListener != null) ? environmentUpdateListener
				: ConfigDataEnvironmentUpdateListener.NONE;

		// 从spring.factories中拿到ConfigDataLoader，默认会有两个：
		// ConfigTreeConfigDataLoader和StandardConfigDataLoader
		this.loaders = new ConfigDataLoaders(logFactory, bootstrapContext, resourceLoader.getClassLoader());

		// 每个Contributor要么对应一个PropertySource，要么对应一个location（配置文件路径或所在目录）
		this.contributors = createContributors(binder);
	}

	protected ConfigDataLocationResolvers createConfigDataLocationResolvers(DeferredLogFactory logFactory,
			ConfigurableBootstrapContext bootstrapContext, Binder binder, ResourceLoader resourceLoader) {
		return new ConfigDataLocationResolvers(logFactory, bootstrapContext, binder, resourceLoader);
	}

	private ConfigDataEnvironmentContributors createContributors(Binder binder) {
		this.logger.trace("Building config data environment contributors");
		MutablePropertySources propertySources = this.environment.getPropertySources();
		List<ConfigDataEnvironmentContributor> contributors = new ArrayList<>(propertySources.size() + 10);
		PropertySource<?> defaultPropertySource = null;

		// 把目前Environment中的每个PropertySource都生成对应的Contributor
		// 类型为EXISTING
		for (PropertySource<?> propertySource : propertySources) {
			if (DefaultPropertiesPropertySource.hasMatchingName(propertySource)) {
				defaultPropertySource = propertySource;
			}
			else {
				this.logger.trace(LogMessage.format("Creating wrapped config data contributor for '%s'",
						propertySource.getName()));
				contributors.add(ConfigDataEnvironmentContributor.ofExisting(propertySource));
			}
		}

		// 这才是核心，根据指定路径或默认路径生成ConfigDataEnvironmentContributor，类型为INITIAL_IMPORT
		contributors.addAll(getInitialImportContributors(binder));

		// 把defaultProperties对应的Contributor放到最后
		if (defaultPropertySource != null) {
			this.logger.trace("Creating wrapped config data contributor for default property source");
			contributors.add(ConfigDataEnvironmentContributor.ofExisting(defaultPropertySource));
		}
		return createContributors(contributors);
	}

	protected ConfigDataEnvironmentContributors createContributors(
			List<ConfigDataEnvironmentContributor> contributors) {
		return new ConfigDataEnvironmentContributors(this.logFactory, this.bootstrapContext, contributors);
	}

	ConfigDataEnvironmentContributors getContributors() {
		return this.contributors;
	}

	private List<ConfigDataEnvironmentContributor> getInitialImportContributors(Binder binder) {
		List<ConfigDataEnvironmentContributor> initialContributors = new ArrayList<>();

		// 可以通过spring.config.import来增加路径
		addInitialImportContributors(initialContributors, bindLocations(binder, IMPORT_PROPERTY, EMPTY_LOCATIONS));

		// 可以通过spring.config.additional-location来添加某个配置文件所在的目录
		addInitialImportContributors(initialContributors, bindLocations(binder, ADDITIONAL_LOCATION_PROPERTY, EMPTY_LOCATIONS));

		// 可以通过spring.config.location来指定配置文件路径，默认为
		// optional:classpath:/;optional:classpath:/config/
		// optional:file:./;optional:file:./config/;optional:file:./config/*/
		// 如果指定了就不会用默认的了
		addInitialImportContributors(initialContributors,
				bindLocations(binder, LOCATION_PROPERTY, DEFAULT_SEARCH_LOCATIONS));

		// 根据获取到的每个路径生成对应的Contributor，类型为INITIAL_IMPORT

		return initialContributors;
	}

	private ConfigDataLocation[] bindLocations(Binder binder, String propertyName, ConfigDataLocation[] other) {
		return binder.bind(propertyName, CONFIG_DATA_LOCATION_ARRAY).orElse(other);
	}

	private void addInitialImportContributors(List<ConfigDataEnvironmentContributor> initialContributors,
			ConfigDataLocation[] locations) {
		// 来了个倒序...
		for (int i = locations.length - 1; i >= 0; i--) {
			initialContributors.add(createInitialImportContributor(locations[i]));
		}
	}

	private ConfigDataEnvironmentContributor createInitialImportContributor(ConfigDataLocation location) {
		this.logger.trace(LogMessage.format("Adding initial config data import from location '%s'", location));
		return ConfigDataEnvironmentContributor.ofInitialImport(location);
	}

	/**
	 * Process all contributions and apply any newly imported property sources to the
	 * {@link Environment}.
	 */
	void processAndApply() {

		// 用来加载和解析配置文件的
		ConfigDataImporter importer = new ConfigDataImporter(this.logFactory, this.notFoundAction, this.resolvers,
				this.loaders);
		registerBootstrapBinder(this.contributors, null, DENY_INACTIVE_BINDING);

		// 处理INITIAL_IMPORT状态的ConfigDataEnvironmentContributor
		// 实际上就是去对应的配置文件路径下找到配置文件并解析，每解析一个配置文件就会生成一个ConfigDataEnvironmentContributor
		// 所以可以会解析出来多个，那这多个就是对应ConfigDataEnvironmentContributor的children
		ConfigDataEnvironmentContributors contributors = processInitial(this.contributors, importer);

		// 确定当前所在的云平台
		ConfigDataActivationContext activationContext = createActivationContext(
				contributors.getBinder(null, BinderOption.FAIL_ON_BIND_TO_INACTIVE_SOURCE));

		// 没啥用
		contributors = processWithoutProfiles(contributors, importer, activationContext);

		// 确定当前被激活的profile
		// 获取spring.profiles.active=dev
		activationContext = withProfiles(contributors, activationContext);

		// 然后找指定profile的配置文件，比如application-dev.properties
		contributors = processWithProfiles(contributors, importer, activationContext);

		// 根据当前云平台和指定的profile，过滤每个配置文件，配置文件中可以指定激活条件
		// spring.config.activate.on-profile=dev
		// spring.config.activate.on-cloudPlatform=CLOUD_FOUNDRY
		// 符合激活条件则将PropertySource添加到Environment中去
		applyToEnvironment(contributors, activationContext, importer.getLoadedLocations(),
				importer.getOptionalLocations());
	}

	private ConfigDataEnvironmentContributors processInitial(ConfigDataEnvironmentContributors contributors,
			ConfigDataImporter importer) {
		this.logger.trace("Processing initial config data environment contributors without activation context");

		// profile未知的情况下
		contributors = contributors.withProcessedImports(importer, null);

		registerBootstrapBinder(contributors, null, DENY_INACTIVE_BINDING);
		return contributors;
	}

	private ConfigDataActivationContext createActivationContext(Binder initialBinder) {
		this.logger.trace("Creating config data activation context from initial contributions");
		try {
			return new ConfigDataActivationContext(this.environment, initialBinder);
		}
		catch (BindException ex) {
			if (ex.getCause() instanceof InactiveConfigDataAccessException) {
				throw (InactiveConfigDataAccessException) ex.getCause();
			}
			throw ex;
		}
	}

	private ConfigDataEnvironmentContributors processWithoutProfiles(ConfigDataEnvironmentContributors contributors,
			ConfigDataImporter importer, ConfigDataActivationContext activationContext) {
		this.logger.trace("Processing config data environment contributors with initial activation context");
		contributors = contributors.withProcessedImports(importer, activationContext);
		registerBootstrapBinder(contributors, activationContext, DENY_INACTIVE_BINDING);
		return contributors;
	}

	private ConfigDataActivationContext withProfiles(ConfigDataEnvironmentContributors contributors,
			ConfigDataActivationContext activationContext) {
		this.logger.trace("Deducing profiles from current config data environment contributors");
		Binder binder = contributors.getBinder(activationContext,
				(contributor) -> !contributor.hasConfigDataOption(ConfigData.Option.IGNORE_PROFILES),
				BinderOption.FAIL_ON_BIND_TO_INACTIVE_SOURCE);
		try {
			Set<String> additionalProfiles = new LinkedHashSet<>(this.additionalProfiles);
			additionalProfiles.addAll(getIncludedProfiles(contributors, activationContext));
			Profiles profiles = new Profiles(this.environment, binder, additionalProfiles);
			return activationContext.withProfiles(profiles);
		}
		catch (BindException ex) {
			if (ex.getCause() instanceof InactiveConfigDataAccessException) {
				throw (InactiveConfigDataAccessException) ex.getCause();
			}
			throw ex;
		}
	}

	private Collection<? extends String> getIncludedProfiles(ConfigDataEnvironmentContributors contributors,
			ConfigDataActivationContext activationContext) {
		PlaceholdersResolver placeholdersResolver = new ConfigDataEnvironmentContributorPlaceholdersResolver(
				contributors, activationContext, null, true);
		Set<String> result = new LinkedHashSet<>();
		for (ConfigDataEnvironmentContributor contributor : contributors) {
			ConfigurationPropertySource source = contributor.getConfigurationPropertySource();
			if (source != null && !contributor.hasConfigDataOption(ConfigData.Option.IGNORE_PROFILES)) {
				Binder binder = new Binder(Collections.singleton(source), placeholdersResolver);
				binder.bind(Profiles.INCLUDE_PROFILES, STRING_LIST).ifBound((includes) -> {
					if (!contributor.isActive(activationContext)) {
						InactiveConfigDataAccessException.throwIfPropertyFound(contributor, Profiles.INCLUDE_PROFILES);
						InactiveConfigDataAccessException.throwIfPropertyFound(contributor,
								Profiles.INCLUDE_PROFILES.append("[0]"));
					}
					result.addAll(includes);
				});
			}
		}
		return result;
	}

	private ConfigDataEnvironmentContributors processWithProfiles(ConfigDataEnvironmentContributors contributors,
			ConfigDataImporter importer, ConfigDataActivationContext activationContext) {
		this.logger.trace("Processing config data environment contributors with profile activation context");
		contributors = contributors.withProcessedImports(importer, activationContext);
		registerBootstrapBinder(contributors, activationContext, ALLOW_INACTIVE_BINDING);
		return contributors;
	}

	private void registerBootstrapBinder(ConfigDataEnvironmentContributors contributors,
			ConfigDataActivationContext activationContext, BinderOption... binderOptions) {
		this.bootstrapContext.register(Binder.class, InstanceSupplier
				.from(() -> contributors.getBinder(activationContext, binderOptions)).withScope(Scope.PROTOTYPE));
	}

	private void applyToEnvironment(ConfigDataEnvironmentContributors contributors,
			ConfigDataActivationContext activationContext, Set<ConfigDataLocation> loadedLocations,
			Set<ConfigDataLocation> optionalLocations) {
		checkForInvalidProperties(contributors);
		checkMandatoryLocations(contributors, activationContext, loadedLocations, optionalLocations);

		// 把contributors中的PropertySource添加到environment中
		MutablePropertySources propertySources = this.environment.getPropertySources();

		// 把配置文件对应的PropertySource添加到environment中去
		applyContributor(contributors, activationContext, propertySources);

		// 把defaultProperties移至最后
		DefaultPropertiesPropertySource.moveToEnd(propertySources);

		Profiles profiles = activationContext.getProfiles();
		this.logger.trace(LogMessage.format("Setting default profiles: %s", profiles.getDefault()));
		this.environment.setDefaultProfiles(StringUtils.toStringArray(profiles.getDefault()));
		this.logger.trace(LogMessage.format("Setting active profiles: %s", profiles.getActive()));
		this.environment.setActiveProfiles(StringUtils.toStringArray(profiles.getActive()));
		this.environmentUpdateListener.onSetProfiles(profiles);
	}

	private void applyContributor(ConfigDataEnvironmentContributors contributors,
			ConfigDataActivationContext activationContext, MutablePropertySources propertySources) {
		this.logger.trace("Applying config data environment contributions");
		for (ConfigDataEnvironmentContributor contributor : contributors) {
			PropertySource<?> propertySource = contributor.getPropertySource();
			if (contributor.getKind() == ConfigDataEnvironmentContributor.Kind.BOUND_IMPORT && propertySource != null) {

				// activationContext中记录了当前的云环境和profile
				if (!contributor.isActive(activationContext)) {
					this.logger.trace(
							LogMessage.format("Skipping inactive property source '%s'", propertySource.getName()));
				}
				else {
					this.logger
							.trace(LogMessage.format("Adding imported property source '%s'", propertySource.getName()));
					// 把contributor中的propertySource添加到Environment中的propertySources
					propertySources.addLast(propertySource);
					this.environmentUpdateListener.onPropertySourceAdded(propertySource, contributor.getLocation(),
							contributor.getResource());
				}
			}
		}
	}

	private void checkForInvalidProperties(ConfigDataEnvironmentContributors contributors) {
		for (ConfigDataEnvironmentContributor contributor : contributors) {
			InvalidConfigDataPropertyException.throwOrWarn(this.logger, contributor);
		}
	}

	private void checkMandatoryLocations(ConfigDataEnvironmentContributors contributors,
			ConfigDataActivationContext activationContext, Set<ConfigDataLocation> loadedLocations,
			Set<ConfigDataLocation> optionalLocations) {
		Set<ConfigDataLocation> mandatoryLocations = new LinkedHashSet<>();
		for (ConfigDataEnvironmentContributor contributor : contributors) {
			if (contributor.isActive(activationContext)) {
				mandatoryLocations.addAll(getMandatoryImports(contributor));
			}
		}
		for (ConfigDataEnvironmentContributor contributor : contributors) {
			if (contributor.getLocation() != null) {
				mandatoryLocations.remove(contributor.getLocation());
			}
		}
		mandatoryLocations.removeAll(loadedLocations);
		mandatoryLocations.removeAll(optionalLocations);
		if (!mandatoryLocations.isEmpty()) {
			for (ConfigDataLocation mandatoryLocation : mandatoryLocations) {
				this.notFoundAction.handle(this.logger, new ConfigDataLocationNotFoundException(mandatoryLocation));
			}
		}
	}

	private Set<ConfigDataLocation> getMandatoryImports(ConfigDataEnvironmentContributor contributor) {
		List<ConfigDataLocation> imports = contributor.getImports();
		Set<ConfigDataLocation> mandatoryLocations = new LinkedHashSet<>(imports.size());
		for (ConfigDataLocation location : imports) {
			if (!location.isOptional()) {
				mandatoryLocations.add(location);
			}
		}
		return mandatoryLocations;
	}

}
