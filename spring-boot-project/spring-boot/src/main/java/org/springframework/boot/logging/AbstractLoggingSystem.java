/*
 * Copyright 2012-2019 the original author or authors.
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

package org.springframework.boot.logging;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;
import org.springframework.util.SystemPropertyUtils;

/**
 * Abstract base class for {@link LoggingSystem} implementations.
 *
 * @author Phillip Webb
 * @author Dave Syer
 * @since 1.0.0
 */
public abstract class AbstractLoggingSystem extends LoggingSystem {

	// 日志配置比较器
	protected static final Comparator<LoggerConfiguration> CONFIGURATION_COMPARATOR = new LoggerConfigurationComparator(
			ROOT_LOGGER_NAME);

	private final ClassLoader classLoader;

	public AbstractLoggingSystem(ClassLoader classLoader) {
		this.classLoader = classLoader;
	}

	@Override
	public void beforeInitialize() {
	}

	@Override
	public void initialize(LoggingInitializationContext initializationContext, String configLocation, LogFile logFile) {
		// 是否指定了配置文件的位置
		if (StringUtils.hasLength(configLocation)) {
			initializeWithSpecificConfig(initializationContext, configLocation, logFile);
			return;
		}
		// 默认位置初始化
		initializeWithConventions(initializationContext, logFile);
	}

	private void initializeWithSpecificConfig(LoggingInitializationContext initializationContext, String configLocation,
			LogFile logFile) {
		// 解析出配置文件的地址，
		configLocation = SystemPropertyUtils.resolvePlaceholders(configLocation);
		loadConfiguration(initializationContext, configLocation, logFile);
	}

	private void initializeWithConventions(LoggingInitializationContext initializationContext, LogFile logFile) {
		// 获取到日志配置文件地址 比如：logback.xml
		String config = getSelfInitializationConfig();
		if (config != null && logFile == null) {
			// self initialization has occurred, reinitialize in case of property changes
			// 发生自初始化，在属性更改时重新初始化
			reinitialize(initializationContext);
			return;
		}
		// 没有指定配置文件名称  比如：logback-spring.xml
		if (config == null) {
			config = getSpringInitializationConfig();
		}
		if (config != null) {
			loadConfiguration(initializationContext, config, logFile);
			return;
		}
		// 没有加载到任何的日志配置文件
		loadDefaults(initializationContext, logFile);
	}

	/**
	 * Return any self initialization config that has been applied. By default this method
	 * checks {@link #getStandardConfigLocations()} and assumes that any file that exists
	 * will have been applied.
	 * @return the self initialization config or {@code null}
	 */
	protected String getSelfInitializationConfig() {
		return findConfig(getStandardConfigLocations());
	}

	/**
	 * Return any spring specific initialization config that should be applied. By default
	 * this method checks {@link #getSpringConfigLocations()}.
	 * 返回应应用的任何特定于弹簧的初始化配置。默认情况下，此方法检查getSpringConfigLocations（）
	 * @return the spring initialization config or {@code null}
	 */
	protected String getSpringInitializationConfig() {
		return findConfig(getSpringConfigLocations());
	}

	/**
	 * 返回配置文件资源路径
	 * @param locations
	 * @return
	 */
	private String findConfig(String[] locations) {
		for (String location : locations) {
			ClassPathResource resource = new ClassPathResource(location, this.classLoader);
			if (resource.exists()) {
				return "classpath:" + location;
			}
		}
		return null;
	}

	/**
	 * Return the standard config locations for this system.
	 * 返回此系统的标准配置位置。
	 *
	 * @return the standard config locations
	 * @see #getSelfInitializationConfig()
	 */
	protected abstract String[] getStandardConfigLocations();

	/**
	 * Return the spring config locations for this system. By default this method returns
	 * a set of locations based on {@link #getStandardConfigLocations()}.
	 * @return the spring config locations
	 * 返回此系统的弹簧配置位置。默认情况下，此方法基于getStandardConfigLocations（）返回一组位置
	 * @see #getSpringInitializationConfig()
	 */
	protected String[] getSpringConfigLocations() {
		String[] locations = getStandardConfigLocations();
		for (int i = 0; i < locations.length; i++) {
			String extension = StringUtils.getFilenameExtension(locations[i]);
			locations[i] = locations[i].substring(0, locations[i].length() - extension.length() - 1) + "-spring."
					+ extension;
		}
		return locations;
	}

	/**
	 * Load sensible defaults for the logging system.
	 * @param initializationContext the logging initialization context
	 * @param logFile the file to load or {@code null} if no log file is to be written
	 */
	protected abstract void loadDefaults(LoggingInitializationContext initializationContext, LogFile logFile);

	/**
	 * Load a specific configuration.
	 * 加载特定配置
	 *
	 * @param initializationContext the logging initialization context
	 *                              日志初始化上下文
	 * @param location the location of the configuration to load (never {@code null})
	 *                 要加载的配置的位置（从不为空）
	 * @param logFile the file to load or {@code null} if no log file is to be written
	 *                要加载的文件，如果不写入日志文件，则为null
	 */
	protected abstract void loadConfiguration(LoggingInitializationContext initializationContext, String location,
			LogFile logFile);

	/**
	 * Reinitialize the logging system if required. Called when
	 * {@link #getSelfInitializationConfig()} is used and the log file hasn't changed. May
	 * be used to reload configuration (for example to pick up additional System properties).
	 * 如果需要，请重新初始化日志记录系统。当使用getSelfInitializationConfig（）并且日志文件没有更改时调用。
	 * 可用于重新加载配置（例如获取其他系统属性）。
	 * @param initializationContext the logging initialization context
	 */
	protected void reinitialize(LoggingInitializationContext initializationContext) {
	}

	protected final ClassLoader getClassLoader() {
		return this.classLoader;
	}

	protected final String getPackagedConfigFile(String fileName) {
		String defaultPath = ClassUtils.getPackageName(getClass());
		defaultPath = defaultPath.replace('.', '/');
		defaultPath = defaultPath + "/" + fileName;
		defaultPath = "classpath:" + defaultPath;
		return defaultPath;
	}

	protected final void applySystemProperties(Environment environment, LogFile logFile) {
		new LoggingSystemProperties(environment).apply(logFile);
	}

	/**
	 * Maintains a mapping between native levels and {@link LogLevel}.
	 *
	 * @param <T> the native level type
	 */
	protected static class LogLevels<T> {

		private final Map<LogLevel, T> systemToNative;

		private final Map<T, LogLevel> nativeToSystem;

		public LogLevels() {
			this.systemToNative = new EnumMap<>(LogLevel.class);
			this.nativeToSystem = new HashMap<>();
		}

		public void map(LogLevel system, T nativeLevel) {
			this.systemToNative.putIfAbsent(system, nativeLevel);
			this.nativeToSystem.putIfAbsent(nativeLevel, system);
		}

		public LogLevel convertNativeToSystem(T level) {
			return this.nativeToSystem.get(level);
		}

		public T convertSystemToNative(LogLevel level) {
			return this.systemToNative.get(level);
		}

		public Set<LogLevel> getSupported() {
			return new LinkedHashSet<>(this.nativeToSystem.values());
		}

	}

}
