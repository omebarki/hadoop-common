/**
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

package org.apache.hadoop.yarn.server.nodemanager;

import static org.apache.hadoop.yarn.server.nodemanager.NMConfig.NM_CONTAINER_EXECUTOR_CLASS;
import static org.apache.hadoop.yarn.server.nodemanager.NMConfig.NM_KEYTAB;

import java.io.IOException;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;

import org.apache.hadoop.NodeHealthCheckerService;
import org.apache.hadoop.NodeHealthStatus;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.security.SecurityUtil;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.hadoop.yarn.ApplicationID;
import org.apache.hadoop.yarn.ContainerID;
import org.apache.hadoop.yarn.YarnException;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.event.AsyncDispatcher;
import org.apache.hadoop.yarn.event.Dispatcher;
import org.apache.hadoop.yarn.server.YarnServerConfig;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.ContainerManagerImpl;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.application.Application;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.container.Container;
import org.apache.hadoop.yarn.service.CompositeService;
import org.apache.hadoop.yarn.service.Service;

public class NodeManager extends CompositeService {

  public NodeManager() {
    super(NodeManager.class.getName());

    Context context = new NMContext();

    YarnConfiguration conf = new YarnConfiguration();
    ContainerExecutor exec = ReflectionUtils.newInstance(
        conf.getClass(NM_CONTAINER_EXECUTOR_CLASS,
          DefaultContainerExecutor.class, ContainerExecutor.class), conf);
    DeletionService del = new DeletionService(exec);
    addService(del);

    // NodeManager level dispatcher
    Dispatcher dispatcher = new AsyncDispatcher();

    NodeHealthCheckerService healthChecker = null;
    if (NodeHealthCheckerService.shouldRun(conf)) {
      healthChecker = new NodeHealthCheckerService();
      addService(healthChecker);
    }

    // StatusUpdater should be added first so that it can start first. Once it
    // contacts RM, does registration and gets tokens, then only
    // ContainerManager can start.
    NodeStatusUpdater nodeStatusUpdater =
        createNodeStatusUpdater(context, dispatcher, healthChecker);
    addService(nodeStatusUpdater);

    NodeResourceMonitor nodeResourceMonitor = createNodeResourceMonitor();
    addService(nodeResourceMonitor);

    ContainerManagerImpl containerManager =
        createContainerManager(context, exec, del, nodeStatusUpdater);
    addService(containerManager);

    Service webServer = createWebServer();
    addService(webServer);

    dispatcher.register(ContainerManagerEventType.class, containerManager);
  }

  protected NodeStatusUpdater createNodeStatusUpdater(Context context,
      Dispatcher dispatcher, NodeHealthCheckerService healthChecker) {
    return new NodeStatusUpdaterImpl(context, dispatcher, healthChecker);
  }

  protected NodeResourceMonitor createNodeResourceMonitor() {
    return new NodeResourceMonitorImpl();
  }

  protected ContainerManagerImpl createContainerManager(Context context,
      ContainerExecutor exec, DeletionService del,
      NodeStatusUpdater nodeStatusUpdater) {
    return new ContainerManagerImpl(context, exec, del, nodeStatusUpdater);
  }

  protected WebServer createWebServer() {
    return new WebServer();
  }

  protected void doSecureLogin() throws IOException {
    SecurityUtil.login(getConfig(), NM_KEYTAB,
        YarnServerConfig.NM_SERVER_PRINCIPAL_KEY);
  }

  @Override
  public void init(Configuration conf) {
    Runtime.getRuntime().addShutdownHook(new Thread() {
          @Override
          public void run() {
            NodeManager.this.stop();
          }
        });
    super.init(conf);
    // TODO add local dirs to del
  }

  @Override
  public void start() {
    try {
      doSecureLogin();
    } catch (IOException e) {
      throw new YarnException("Failed NodeManager login", e);
    }
    super.start();
  }

  public static class NMContext implements Context {

    private final ConcurrentMap<ApplicationID, Application> applications =
        new ConcurrentHashMap<ApplicationID, Application>();
    private final ConcurrentMap<ContainerID, Container> containers =
      new ConcurrentSkipListMap<ContainerID,Container>(
          new Comparator<ContainerID>() {
            @Override
            public int compare(ContainerID a, ContainerID b) {
              if (a.appID.id == b.appID.id) {
                return a.id - b.id;
              }
              return a.appID.id - b.appID.id;
            }
            @Override
            public boolean equals(Object other) {
              return getClass().equals(other.getClass());
            }
          });

    private final NodeHealthStatus nodeHealthStatus = new NodeHealthStatus();

    public NMContext() {
    }

    @Override
    public ConcurrentMap<ApplicationID, Application> getApplications() {
      return this.applications;
    }

    @Override
    public ConcurrentMap<ContainerID, Container> getContainers() {
      return this.containers;
    }

    @Override
    public NodeHealthStatus getNodeHealthStatus() {
      return this.nodeHealthStatus;
    }
  }

  public static void main(String[] args) {
    NodeManager nodeManager = new NodeManager();
    YarnConfiguration conf = new YarnConfiguration();
    nodeManager.init(conf);
    nodeManager.start();
  }

}
