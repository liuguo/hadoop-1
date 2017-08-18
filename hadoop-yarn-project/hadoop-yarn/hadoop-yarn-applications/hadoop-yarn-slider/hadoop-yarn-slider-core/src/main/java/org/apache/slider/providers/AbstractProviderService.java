/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.slider.providers;

import org.apache.hadoop.service.AbstractService;
import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.yarn.api.ApplicationConstants;
import org.apache.hadoop.yarn.api.records.Container;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.ContainerStatus;
import org.apache.hadoop.yarn.service.conf.SliderKeys;
import org.apache.hadoop.yarn.service.provider.ProviderService;
import org.apache.hadoop.yarn.service.provider.ProviderUtils;
import org.apache.hadoop.yarn.service.timelineservice.ServiceTimelinePublisher;
import org.apache.slider.api.resource.Application;
import org.apache.slider.api.resource.Component;
import org.apache.slider.api.resource.ContainerState;
import org.apache.slider.common.tools.SliderFileSystem;
import org.apache.slider.common.tools.SliderUtils;
import org.apache.slider.core.exceptions.SliderException;
import org.apache.slider.core.launch.CommandLineBuilder;
import org.apache.slider.core.launch.ContainerLauncher;
import org.apache.slider.core.registry.docstore.PublishedConfiguration;
import org.apache.slider.server.appmaster.state.RoleInstance;
import org.apache.slider.server.appmaster.state.StateAccessForProviders;
import org.apache.slider.server.services.yarnregistry.YarnRegistryViewForProviders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.Map.Entry;

import static org.apache.hadoop.yarn.service.utils.ServiceApiUtil.$;

public abstract class AbstractProviderService extends AbstractService
    implements ProviderService, SliderKeys {

  protected static final Logger log =
      LoggerFactory.getLogger(AbstractProviderService.class);
  private static final ProviderUtils providerUtils = new ProviderUtils();
  protected StateAccessForProviders amState;
  protected YarnRegistryViewForProviders yarnRegistry;
  private ServiceTimelinePublisher serviceTimelinePublisher;

  protected AbstractProviderService(String name) {
    super(name);
  }

  public abstract void processArtifact(ContainerLauncher launcher,
      Application application, RoleInstance roleInstance,
      SliderFileSystem fileSystem) throws IOException;


  public void buildContainerLaunchContext(ContainerLauncher launcher,
      Application application, Container container, ProviderRole providerRole,
      SliderFileSystem fileSystem, RoleInstance roleInstance)
      throws IOException, SliderException {
    Component component = providerRole.component;
    processArtifact(launcher, application, roleInstance, fileSystem);

    // Generate tokens (key-value pair) for config substitution.
    // Get pre-defined tokens
    Map<String, String> globalTokens = amState.getGlobalSubstitutionTokens();
    Map<String, String> tokensForSubstitution = providerUtils
        .initCompTokensForSubstitute(null);
    tokensForSubstitution.putAll(globalTokens);
    // Set the environment variables in launcher
    launcher.putEnv(SliderUtils
        .buildEnvMap(component.getConfiguration(), tokensForSubstitution));
    launcher.setEnv("WORK_DIR", ApplicationConstants.Environment.PWD.$());
    launcher.setEnv("LOG_DIR", ApplicationConstants.LOG_DIR_EXPANSION_VAR);
    if (System.getenv(HADOOP_USER_NAME) != null) {
      launcher.setEnv(HADOOP_USER_NAME, System.getenv(HADOOP_USER_NAME));
    }
    launcher.setEnv("LANG", "en_US.UTF-8");
    launcher.setEnv("LC_ALL", "en_US.UTF-8");
    launcher.setEnv("LANGUAGE", "en_US.UTF-8");

    for (Entry<String, String> entry : launcher.getEnv().entrySet()) {
      tokensForSubstitution.put($(entry.getKey()), entry.getValue());
    }
    providerUtils.addComponentHostTokens(tokensForSubstitution, amState);

    // create config file on hdfs and add local resource

    // substitute launch command
    String launchCommand = ProviderUtils
        .substituteStrWithTokens(component.getLaunchCommand(),
            tokensForSubstitution);
    CommandLineBuilder operation = new CommandLineBuilder();
    operation.add(launchCommand);
    operation.addOutAndErrFiles(OUT_FILE, ERR_FILE);
    launcher.addCommand(operation.build());

    // publish exports
    providerUtils
        .substituteMapWithTokens(application.getQuicklinks(), tokensForSubstitution);
    PublishedConfiguration pubconf = new PublishedConfiguration(QUICK_LINKS,
        application.getQuicklinks().entrySet());
    amState.getPublishedSliderConfigurations().put(QUICK_LINKS, pubconf);
    if (serviceTimelinePublisher != null) {
      serviceTimelinePublisher.serviceAttemptUpdated(application);
    }
  }

  public boolean processContainerStatus(ContainerId containerId,
      ContainerStatus status) {
    log.debug("Handling container status: {}", status);
    if (SliderUtils.isEmpty(status.getIPs()) ||
        SliderUtils.isUnset(status.getHost())) {
      return true;
    }
    RoleInstance instance = amState.getOwnedContainer(containerId);
    if (instance == null) {
      // container is completed?
      return false;
    }

    // TODO publish ip and host
    org.apache.slider.api.resource.Container container =
        instance.providerRole.component.getContainer(containerId.toString());
    if (container != null) {
      container.setIp(StringUtils.join(",", status.getIPs()));
      container.setHostname(status.getHost());
      container.setState(ContainerState.READY);
    } else {
      log.warn(containerId + " not found in Application!");
    }
    return false;
  }

}