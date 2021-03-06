/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.geode.session.tests;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import org.apache.geode.internal.AvailablePortHelper;
import org.apache.geode.management.internal.cli.i18n.CliStrings;
import org.apache.geode.management.internal.cli.util.CommandStringBuilder;
import org.apache.geode.test.junit.rules.GfshShellConnectionRule;
import org.apache.geode.test.dunit.rules.LocatorServerStartupRule;
import org.apache.geode.test.dunit.standalone.VersionManager;
import org.apache.geode.test.junit.categories.BackwardCompatibilityTest;
import org.apache.geode.test.junit.categories.DistributedTest;
import org.apache.geode.test.junit.runners.CategoryWithParameterizedRunnerFactory;

/**
 * This test iterates through the versions of Geode and executes session client compatibility with
 * the current version of Geode.
 */
@Category({DistributedTest.class, BackwardCompatibilityTest.class})
@RunWith(Parameterized.class)
@Parameterized.UseParametersRunnerFactory(CategoryWithParameterizedRunnerFactory.class)
public class TomcatSessionBackwardsCompatibilityTest {

  @Parameterized.Parameters
  public static Collection<String> data() {
    List<String> result = VersionManager.getInstance().getVersionsWithoutCurrent();
    result.removeIf(s -> Integer.parseInt(s) < 120);
    if (result.size() < 1) {
      throw new RuntimeException("No older versions of Geode were found to test against");
    }
    return result;
  }

  @Rule
  public transient GfshShellConnectionRule gfsh = new GfshShellConnectionRule();

  @Rule
  public transient LocatorServerStartupRule locatorStartup = new LocatorServerStartupRule();

  @Rule
  public transient TestName testName = new TestName();

  public transient Client client;
  public transient ContainerManager manager;

  File oldBuild;
  File oldModules;

  TomcatInstall tomcat7079AndOldModules;
  TomcatInstall tomcat7079AndCurrentModules;
  TomcatInstall tomcat8AndOldModules;
  TomcatInstall tomcat8AndCurrentModules;

  int locatorPort;
  String classPathTomcat7079;
  String classPathTomcat8;

  public TomcatSessionBackwardsCompatibilityTest(String version) {
    VersionManager versionManager = VersionManager.getInstance();
    String installLocation = versionManager.getInstall(version);
    oldBuild = new File(installLocation);
    oldModules = new File(installLocation + "/tools/Modules/");
  }

  protected void startServer(String name, String classPath, int locatorPort) throws Exception {
    CommandStringBuilder command = new CommandStringBuilder(CliStrings.START_SERVER);
    command.addOption(CliStrings.START_SERVER__NAME, name);
    command.addOption(CliStrings.START_SERVER__SERVER_PORT, "0");
    command.addOption(CliStrings.START_SERVER__CLASSPATH, classPath);
    command.addOption(CliStrings.START_SERVER__LOCATORS, "localhost[" + locatorPort + "]");
    gfsh.executeAndVerifyCommand(command.toString());
  }

  protected void startLocator(String name, String classPath, int port) throws Exception {
    CommandStringBuilder locStarter = new CommandStringBuilder(CliStrings.START_LOCATOR);
    locStarter.addOption(CliStrings.START_LOCATOR__MEMBER_NAME, name);
    locStarter.addOption(CliStrings.START_LOCATOR__CLASSPATH, classPath);
    locStarter.addOption(CliStrings.START_LOCATOR__PORT, Integer.toString(port));
    gfsh.executeAndVerifyCommand(locStarter.toString());

  }

  @Before
  public void setup() throws Exception {
    tomcat7079AndOldModules = new TomcatInstall(TomcatInstall.TomcatVersion.TOMCAT779,
        ContainerInstall.ConnectionType.CLIENT_SERVER,
        ContainerInstall.DEFAULT_INSTALL_DIR + "Tomcat7079AndOldModules",
        oldModules.getAbsolutePath(), oldBuild.getAbsolutePath() + "/lib");

    tomcat7079AndCurrentModules = new TomcatInstall(TomcatInstall.TomcatVersion.TOMCAT779,
        ContainerInstall.ConnectionType.CLIENT_SERVER,
        ContainerInstall.DEFAULT_INSTALL_DIR + "Tomcat7079AndCurrentModules");

    tomcat8AndOldModules = new TomcatInstall(TomcatInstall.TomcatVersion.TOMCAT8,
        ContainerInstall.ConnectionType.CLIENT_SERVER,
        ContainerInstall.DEFAULT_INSTALL_DIR + "Tomcat8AndOldModules", oldModules.getAbsolutePath(),
        oldBuild.getAbsolutePath() + "/lib");

    tomcat8AndCurrentModules = new TomcatInstall(TomcatInstall.TomcatVersion.TOMCAT8,
        ContainerInstall.ConnectionType.CLIENT_SERVER,
        ContainerInstall.DEFAULT_INSTALL_DIR + "Tomcat8AndCurrentModules");

    classPathTomcat7079 = tomcat7079AndCurrentModules.getHome() + "/lib/*" + File.pathSeparator
        + tomcat7079AndCurrentModules.getHome() + "/bin/*";
    classPathTomcat8 = tomcat8AndCurrentModules.getHome() + "/lib/*" + File.pathSeparator
        + tomcat8AndCurrentModules.getHome() + "/bin/*";

    // Get available port for the locator
    locatorPort = AvailablePortHelper.getRandomAvailableTCPPort();

    tomcat7079AndOldModules.setDefaultLocator("localhost", locatorPort);
    tomcat7079AndCurrentModules.setDefaultLocator("localhost", locatorPort);

    tomcat8AndOldModules.setDefaultLocator("localhost", locatorPort);
    tomcat8AndCurrentModules.setDefaultLocator("localhost", locatorPort);

    client = new Client();
    manager = new ContainerManager();
    // Due to parameterization of the test name, the URI would be malformed. Instead, it strips off
    // the [] symbols
    manager.setTestName(testName.getMethodName().replace("[", "").replace("]", ""));
  }

  private void startClusterWithTomcat(String tomcatClassPath) throws Exception {
    startLocator("loc", tomcatClassPath, locatorPort);
    startServer("server", tomcatClassPath, locatorPort);
  }

  /**
   * Stops all containers that were previously started and cleans up their configurations
   */
  @After
  public void stop() throws Exception {
    manager.stopAllActiveContainers();
    manager.cleanUp();

    CommandStringBuilder locStop = new CommandStringBuilder(CliStrings.STOP_LOCATOR);
    locStop.addOption(CliStrings.STOP_LOCATOR__DIR, "loc");
    gfsh.executeAndVerifyCommand(locStop.toString());

    CommandStringBuilder command = new CommandStringBuilder(CliStrings.STOP_SERVER);
    command.addOption(CliStrings.STOP_SERVER__DIR, "server");
    gfsh.executeAndVerifyCommand(command.toString());
  }

  private void doPutAndGetSessionOnAllClients() throws IOException, URISyntaxException {
    // This has to happen at the start of every test
    manager.startAllInactiveContainers();

    String key = "value_testSessionPersists";
    String value = "Foo";

    client.setPort(Integer.parseInt(manager.getContainerPort(0)));
    Client.Response resp = client.set(key, value);
    String cookie = resp.getSessionCookie();

    for (int i = 0; i < manager.numContainers(); i++) {
      System.out.println("Checking get for container:" + i);
      client.setPort(Integer.parseInt(manager.getContainerPort(i)));
      resp = client.get(key);

      assertEquals("Sessions are not replicating properly", cookie, resp.getSessionCookie());
      assertEquals("Session data is not replicating properly", value, resp.getResponse());
    }
  }

  @Test
  public void tomcat7079WithOldModuleCanDoPuts() throws Exception {
    startClusterWithTomcat(classPathTomcat7079);
    manager.addContainer(tomcat7079AndOldModules);
    manager.addContainer(tomcat7079AndOldModules);
    doPutAndGetSessionOnAllClients();
  }

  @Test
  public void tomcat7079WithOldModulesMixedWithCurrentCanDoPutFromOldModule() throws Exception {
    startClusterWithTomcat(classPathTomcat7079);
    manager.addContainer(tomcat7079AndOldModules);
    manager.addContainer(tomcat7079AndCurrentModules);
    doPutAndGetSessionOnAllClients();
  }

  @Test
  public void tomcat7079WithOldModulesMixedWithCurrentCanDoPutFromCurrentModule() throws Exception {
    startClusterWithTomcat(classPathTomcat7079);
    manager.addContainer(tomcat7079AndCurrentModules);
    manager.addContainer(tomcat7079AndOldModules);
    doPutAndGetSessionOnAllClients();
  }

  @Test
  public void tomcat8WithOldModuleCanDoPuts() throws Exception {
    startClusterWithTomcat(classPathTomcat8);
    manager.addContainer(tomcat8AndOldModules);
    manager.addContainer(tomcat8AndOldModules);
    doPutAndGetSessionOnAllClients();
  }

  @Test
  public void tomcat8WithOldModulesMixedWithCurrentCanDoPutFromOldModule() throws Exception {
    startClusterWithTomcat(classPathTomcat8);
    manager.addContainer(tomcat8AndOldModules);
    manager.addContainer(tomcat8AndCurrentModules);
    doPutAndGetSessionOnAllClients();
  }

  @Test
  public void tomcat8WithOldModulesMixedWithCurrentCanDoPutFromCurrentModule() throws Exception {
    startClusterWithTomcat(classPathTomcat8);
    manager.addContainer(tomcat8AndCurrentModules);
    manager.addContainer(tomcat8AndOldModules);
    doPutAndGetSessionOnAllClients();
  }

}
