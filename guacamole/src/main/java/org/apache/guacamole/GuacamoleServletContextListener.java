/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.guacamole;

import org.apache.guacamole.tunnel.TunnelModule;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Stage;
import com.google.inject.servlet.GuiceServletContextListener;
import java.io.File;
import java.util.List;
import javax.servlet.ServletContextEvent;
import org.apache.guacamole.environment.Environment;
import org.apache.guacamole.extension.ExtensionModule;
import org.apache.guacamole.log.LogModule;
import org.apache.guacamole.net.auth.AuthenticationProvider;
import org.apache.guacamole.properties.BooleanGuacamoleProperty;
import org.apache.guacamole.properties.FileGuacamoleProperties;
import org.apache.guacamole.rest.RESTServiceModule;
import org.apache.guacamole.rest.auth.HashTokenSessionMap;
import org.apache.guacamole.rest.auth.TokenSessionMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A ServletContextListener to listen for initialization of the servlet context
 * in order to set up dependency injection.
 */
public class GuacamoleServletContextListener extends GuiceServletContextListener {

    /**
     * Logger for this class.
     */
    private final Logger logger = LoggerFactory.getLogger(GuacamoleServletContextListener.class);

    /**
     * A property that determines whether environment variables are evaluated
     * to override properties specified in guacamole.properties.
     */
    private static final BooleanGuacamoleProperty ENABLE_ENVIRONMENT_PROPERTIES =
        new BooleanGuacamoleProperty() {
            @Override
            public String getName() {
                return "enable-environment-properties";
            }
        };

    /**
     * The Guacamole server environment.
     */
    private Environment environment;

    /**
     * Singleton instance of a TokenSessionMap.
     */
    private TokenSessionMap sessionMap;

    /**
     * List of all authentication providers from all loaded extensions.
     */
    @Inject
    private List<AuthenticationProvider> authProviders;

    @Override
    public void contextInitialized(ServletContextEvent servletContextEvent) {

        environment = new WebApplicationEnvironment();

        // Read configuration information from GUACAMOLE_HOME/guacamole.properties
        try {
            environment.addGuacamoleProperties(new FileGuacamoleProperties(
                    new File(environment.getGuacamoleHome(), "guacamole.properties")));
        }
        catch (GuacamoleException e) {
            logger.error("Unable to read guacamole.properties: {}", e.getMessage());
            logger.debug("Error reading guacamole.properties.", e);
        }

        // For any values not defined in GUACAMOLE_HOME/guacamole.properties,
        // read from system environment if "enable-environment-properties" is
        // set to "true"
        try {
            if (environment.getProperty(ENABLE_ENVIRONMENT_PROPERTIES, false))
                environment.addGuacamoleProperties(new SystemEnvironmentGuacamoleProperties());
        }
        catch (GuacamoleException e) {
            logger.error("Unable to configure support for environment properties: {}", e.getMessage());
            logger.debug("Error reading \"{}\" property from guacamole.properties.", ENABLE_ENVIRONMENT_PROPERTIES.getName(), e);
        }

        // Now that at least the main guacamole.properties source of
        // configuration information is available, initialize the session map
        sessionMap = new HashTokenSessionMap(environment);

        super.contextInitialized(servletContextEvent);

    }

    @Override
    protected Injector getInjector() {

        // Create injector
        Injector injector = Guice.createInjector(Stage.PRODUCTION,
            new EnvironmentModule(environment),
            new LogModule(environment),
            new ExtensionModule(environment),
            new RESTServiceModule(sessionMap),
            new TunnelModule()
        );

        // Inject any annotated members of this class
        injector.injectMembers(this);

        return injector;

    }

    @Override
    public void contextDestroyed(ServletContextEvent servletContextEvent) {

        super.contextDestroyed(servletContextEvent);

        // Shutdown TokenSessionMap
        if (sessionMap != null)
            sessionMap.shutdown();

        // Unload all extensions
        if (authProviders != null) {
            for (AuthenticationProvider authProvider : authProviders)
                authProvider.shutdown();
        }

    }

}
