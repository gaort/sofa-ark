/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alipay.sofa.ark.container.service.classloader;

import com.alipay.sofa.ark.common.log.ArkLogger;
import com.alipay.sofa.ark.common.log.ArkLoggerFactory;
import com.alipay.sofa.ark.common.util.AssertUtils;
import com.alipay.sofa.ark.exception.ArkException;
import com.alipay.sofa.ark.spi.service.plugin.PluginManagerService;
import com.alipay.sofa.ark.spi.model.Plugin;
import com.alipay.sofa.ark.spi.service.classloader.ClassloaderService;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import sun.misc.URLClassPath;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Classloader Service Implementation
 *
 * @author ruoshan
 * @since 0.1.0
 */
@Singleton
public class ClassloaderServiceImpl implements ClassloaderService {

    private static final ArkLogger                 LOGGER                         = ArkLoggerFactory
                                                                                      .getDefaultLogger();

    private static final String                    JAVA_AGENT_MARK                = "-javaagent:";

    private static final String                    JAVA_AGENT_OPTION_MARK         = "=";

    private static final String                    ARK_SPI_PACKAGES               = "com.alipay.sofa.ark.spi";

    private static final String                    ARK_BOOTSTRAP_CLASS            = "com.alipay.sofa.ark.bootstrap.SofaArkBootstrap";

    private static final String                    ARK_EXPORT_RESOURCE            = "_sofa_ark_export_resource";

    private static final List<String>              SUN_REFLECT_GENERATED_ACCESSOR = new ArrayList<>();

    /* export class and classloader relationship cache */
    private ConcurrentHashMap<String, ClassLoader> exportClassAndClassloaderMap   = new ConcurrentHashMap<>();

    private ClassLoader                            jdkClassloader;
    private ClassLoader                            arkClassloader;
    private ClassLoader                            systemClassloader;
    private ClassLoader                            agentClassLoader;

    @Inject
    private PluginManagerService                   pluginManagerService;

    static {
        SUN_REFLECT_GENERATED_ACCESSOR.add("sun.reflect.GeneratedMethodAccessor");
        SUN_REFLECT_GENERATED_ACCESSOR.add("sun.reflect.GeneratedConstructorAccessor");
        SUN_REFLECT_GENERATED_ACCESSOR.add("sun.reflect.GeneratedSerializationConstructorAccessor");
    }

    @Override
    public boolean isSunReflectClass(String className) {
        for (String sunAccessor : SUN_REFLECT_GENERATED_ACCESSOR) {
            if (className.startsWith(sunAccessor)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isArkSpiClass(String className) {
        return className.equals(ARK_BOOTSTRAP_CLASS) || className.startsWith(ARK_SPI_PACKAGES);
    }

    @Override
    public void prepareExportClassCache() {
        for (Plugin plugin : pluginManagerService.getPluginsInOrder()) {
            for (String exportIndex : plugin.getExportIndex()) {
                exportClassAndClassloaderMap
                    .putIfAbsent(exportIndex, plugin.getPluginClassLoader());
            }
        }
    }

    @Override
    public boolean isClassInImport(String pluginName, String className) {
        Plugin plugin = pluginManagerService.getPluginByName(pluginName);
        AssertUtils.assertNotNull(plugin, "plugin: " + pluginName + " is null");

        for (String importName : plugin.getImportIndex()) {
            if (className.startsWith(importName)) {
                return true;
            }
        }

        return false;
    }

    @Override
    public ClassLoader findImportClassloader(String className) {
        return exportClassAndClassloaderMap.get(className);
    }

    @Override
    public ClassLoader findResourceExportClassloader(String resourceName) {
        if (resourceName.contains(ARK_EXPORT_RESOURCE)) {
            String pluginName = resourceName
                .substring(0, resourceName.indexOf(ARK_EXPORT_RESOURCE));
            Plugin plugin = pluginManagerService.getPluginByName(pluginName);
            if (plugin != null) {
                return plugin.getPluginClassLoader();
            }
        }
        return null;
    }

    @Override
    public ClassLoader getJDKClassloader() {
        return jdkClassloader;
    }

    @Override
    public ClassLoader getArkClassloader() {
        return arkClassloader;
    }

    @Override
    public ClassLoader getSystemClassloader() {
        return systemClassloader;
    }

    @Override
    public ClassLoader getAgentClassloader() {
        return agentClassLoader;
    }

    @Override
    public void init() throws ArkException {
        arkClassloader = this.getClass().getClassLoader();
        systemClassloader = ClassLoader.getSystemClassLoader();
        agentClassLoader = createAgentClassLoader();

        ClassLoader extClassloader = systemClassloader;
        while (extClassloader.getParent() != null) {
            extClassloader = extClassloader.getParent();
        }

        List<URL> jdkUrls = new ArrayList<>();
        try {
            Field ucpField = systemClassloader.getClass().getSuperclass().getDeclaredField("ucp");
            ucpField.setAccessible(true);
            URLClassPath urlClassPath = (URLClassPath) ucpField.get(systemClassloader);
            String javaHome = System.getProperty("java.home").replace(File.separator + "jre", "");
            for (URL url : urlClassPath.getURLs()) {
                if (url.getPath().startsWith(javaHome)) {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug(String.format("Find JDK Url: %s", url));
                    }
                    jdkUrls.add(url);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Meet exception when parse JDK urls", e);
        }

        jdkClassloader = new JDKDelegateClassloader(jdkUrls.toArray(new URL[0]), extClassloader);
    }

    @Override
    public void dispose() throws ArkException {

    }

    private ClassLoader createAgentClassLoader() throws ArkException {

        List<String> inputArguments = AccessController
            .doPrivileged(new PrivilegedAction<List<String>>() {
                @Override
                public List<String> run() {
                    return ManagementFactory.getRuntimeMXBean().getInputArguments();
                }
            });

        List<URL> agentPaths = new ArrayList<>();
        for (String argument : inputArguments) {

            if (!argument.startsWith(JAVA_AGENT_MARK)) {
                continue;
            }

            argument = argument.substring(JAVA_AGENT_MARK.length());

            try {
                String path = argument.split(JAVA_AGENT_OPTION_MARK)[0];
                URL url = new File(path).toURI().toURL();
                agentPaths.add(url);
            } catch (Exception e) {
                throw new ArkException("Failed to create java agent classloader", e);
            }

        }

        return new URLClassLoader(agentPaths.toArray(new URL[] {}), null);

    }
}