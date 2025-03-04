/*
 * Copyright 2022 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.keycloak.operator.testsuite.integration;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.PodSpecFluent.ContainersNested;
import io.fabric8.kubernetes.api.model.PodTemplateSpecFluent.SpecNested;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.NamespacedKubernetesClient;
import io.fabric8.kubernetes.client.readiness.Readiness;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.javaoperatorsdk.operator.Operator;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.quarkiverse.operatorsdk.runtime.QuarkusConfigurationService;
import io.quarkus.logging.Log;
import io.quarkus.test.junit.callback.QuarkusTestAfterEachCallback;
import io.quarkus.test.junit.callback.QuarkusTestMethodContext;

import org.awaitility.Awaitility;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.keycloak.operator.Constants;
import org.keycloak.operator.controllers.KeycloakDeployment;
import org.keycloak.operator.crds.v2alpha1.deployment.Keycloak;
import org.keycloak.operator.crds.v2alpha1.deployment.KeycloakSpecBuilder;
import org.keycloak.operator.crds.v2alpha1.deployment.KeycloakSpecFluent.UnsupportedNested;
import org.keycloak.operator.crds.v2alpha1.deployment.KeycloakStatus;
import org.keycloak.operator.crds.v2alpha1.deployment.spec.UnsupportedSpecFluent.PodTemplateNested;
import org.keycloak.operator.crds.v2alpha1.realmimport.KeycloakRealmImport;
import org.keycloak.operator.testsuite.utils.K8sUtils;
import org.opentest4j.TestAbortedException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.util.TypeLiteral;

import static org.assertj.core.api.Assertions.assertThat;
import static org.keycloak.operator.Utils.isOpenShift;
import static org.keycloak.operator.testsuite.utils.K8sUtils.getResourceFromFile;

public class BaseOperatorTest implements QuarkusTestAfterEachCallback {

  public static final String QUARKUS_KUBERNETES_DEPLOYMENT_TARGET = "quarkus.kubernetes.deployment-target";
  public static final String OPERATOR_DEPLOYMENT_PROP = "test.operator.deployment";
  public static final String TARGET_KUBERNETES_GENERATED_YML_FOLDER = "target/kubernetes/";
  public static final String OPERATOR_KUBERNETES_IP = "test.operator.kubernetes.ip";
  public static final String OPERATOR_CUSTOM_IMAGE = "test.operator.custom.image";

  public static final String TEST_RESULTS_DIR = "target/operator-test-results/";
  public static final String POD_LOGS_DIR = TEST_RESULTS_DIR + "pod-logs/";

  public enum OperatorDeployment {local,remote}

  protected static OperatorDeployment operatorDeployment;
  protected static Instance<Reconciler<? extends HasMetadata>> reconcilers;
  protected static QuarkusConfigurationService configuration;
  protected static KubernetesClient k8sclient;
  protected static String namespace;
  protected static String deploymentTarget;
  protected static String kubernetesIp;
  protected static String customImage;
  private static Operator operator;
  protected static boolean isOpenShift;

  @BeforeAll
  public static void before() throws FileNotFoundException {
    configuration = CDI.current().select(QuarkusConfigurationService.class).get();
    reconcilers = CDI.current().select(new TypeLiteral<>() {});
    operatorDeployment = ConfigProvider.getConfig().getOptionalValue(OPERATOR_DEPLOYMENT_PROP, OperatorDeployment.class).orElse(OperatorDeployment.local);
    deploymentTarget = ConfigProvider.getConfig().getOptionalValue(QUARKUS_KUBERNETES_DEPLOYMENT_TARGET, String.class).orElse("kubernetes");
    customImage = ConfigProvider.getConfig().getOptionalValue(OPERATOR_CUSTOM_IMAGE, String.class).orElse(null);

    setDefaultAwaitilityTimings();
    calculateNamespace();
    createK8sClient();
    kubernetesIp = ConfigProvider.getConfig().getOptionalValue(OPERATOR_KUBERNETES_IP, String.class).orElseGet(() -> {
        try {
            return new URL(k8sclient.getConfiguration().getMasterUrl()).getHost();
        } catch (MalformedURLException e) {
            return "localhost";
        }
    });
    Log.info("Creating CRDs");
    createCRDs(k8sclient);
    createNamespace();
    isOpenShift = isOpenShift(k8sclient);

    if (operatorDeployment == OperatorDeployment.remote) {
      createRBACresourcesAndOperatorDeployment();
    } else {
      createOperator();
      registerReconcilers();
      operator.start();
    }

    deployDB();
  }

  @BeforeEach
  public void beforeEach(TestInfo testInfo) {
    String testClassName = testInfo.getTestClass().map(c -> c.getSimpleName() + ".").orElse("");
    Log.info("\n------- STARTING: " + testClassName + testInfo.getDisplayName() + "\n"
            + "------- Namespace: " + namespace + "\n"
            + "------- Mode: " + ((operatorDeployment == OperatorDeployment.remote) ? "remote" : "local"));
  }

  private static void createK8sClient() {
    k8sclient = new KubernetesClientBuilder().withConfig(new ConfigBuilder(Config.autoConfigure(null)).withNamespace(namespace).build()).build();
  }

  private static void createRBACresourcesAndOperatorDeployment() throws FileNotFoundException {
    Log.info("Creating RBAC and Deployment into Namespace " + namespace);
    K8sUtils.set(k8sclient, new FileInputStream(TARGET_KUBERNETES_GENERATED_YML_FOLDER + deploymentTarget + ".yml"));
  }

  private static void cleanRBACresourcesAndOperatorDeployment() throws FileNotFoundException {
    Log.info("Deleting RBAC from Namespace " + namespace);
    k8sclient.load(new FileInputStream(TARGET_KUBERNETES_GENERATED_YML_FOLDER +deploymentTarget+".yml"))
            .inNamespace(namespace).delete();
  }

  static void createCRDs(KubernetesClient client) throws FileNotFoundException {
    K8sUtils.set(client, new FileInputStream(TARGET_KUBERNETES_GENERATED_YML_FOLDER + "keycloaks.k8s.keycloak.org-v1.yml"));
    K8sUtils.set(client, new FileInputStream(TARGET_KUBERNETES_GENERATED_YML_FOLDER + "keycloakrealmimports.k8s.keycloak.org-v1.yml"));

    Awaitility.await().pollInterval(100, TimeUnit.MILLISECONDS).untilAsserted(() -> client.resources(Keycloak.class).list());
    Awaitility.await().pollInterval(100, TimeUnit.MILLISECONDS).untilAsserted(() -> client.resources(KeycloakRealmImport.class).list());
  }

  private static void registerReconcilers() {
    Log.info("Registering reconcilers for operator : " + operator + " [" + operatorDeployment + "]");

    for (Reconciler<?> reconciler : reconcilers) {
      Log.info("Register and apply : " + reconciler.getClass().getName());
      operator.register(reconciler);
    }
  }

  private static void createOperator() {
    operator = new Operator(overrider -> overrider.withKubernetesClient(k8sclient));
  }

  private static void createNamespace() {
    Log.info("Creating Namespace " + namespace);
    k8sclient.resource(new NamespaceBuilder().withNewMetadata().addToLabels("app","keycloak-test").withName(namespace).endMetadata().build()).create();
    // ensure that the client defaults to the namespace - eventually most of the test code usage of inNamespace can be removed
    k8sclient = k8sclient.adapt(NamespacedKubernetesClient.class).inNamespace(namespace);
  }

  private static void calculateNamespace() {
    namespace = getNewRandomNamespaceName();
  }

  public static String getNewRandomNamespaceName() {
      return "keycloak-test-" + UUID.randomUUID();
  }

  protected static void deployDB() {
    deployDBSecret();

    // DB
    Log.info("Creating new PostgreSQL deployment");
    K8sUtils.set(k8sclient, BaseOperatorTest.class.getResourceAsStream("/example-postgres.yaml"));

    // Check DB has deployed and ready
    Log.info("Checking Postgres is running");
    Awaitility.await()
            .untilAsserted(() -> assertThat(k8sclient.apps().statefulSets().inNamespace(namespace).withName("postgresql-db").get().getStatus().getReadyReplicas()).isEqualTo(1));
  }

  protected static void deployDBSecret() {
    K8sUtils.set(k8sclient, getResourceFromFile("example-db-secret.yaml", Secret.class));
  }

  protected static void deleteDB() {
    // Delete the Postgres StatefulSet
    k8sclient.apps().statefulSets().inNamespace(namespace).withName("postgresql-db").delete();
    Awaitility.await()
            .ignoreExceptions()
            .untilAsserted(() -> {
              Log.infof("Waiting for postgres to be deleted");
              assertThat(k8sclient
                      .apps()
                      .statefulSets()
                      .inNamespace(namespace)
                      .withName("postgresql-db")
                      .get()).isNull();
            });
  }

  // TODO improve this (preferably move to JOSDK)
  protected void savePodLogs() {
    Log.infof("Saving pod logs to %s", POD_LOGS_DIR);
    for (var pod : k8sclient.pods().inNamespace(namespace).list().getItems()) {
      try {
        String podName = pod.getMetadata().getName();
        Log.infof("Processing %s", podName);
        String podLog = k8sclient.pods().inNamespace(namespace).withName(podName).getLog();
        File file = new File(POD_LOGS_DIR + String.format("%s-%s.txt", namespace, podName)); // using namespace for now, if more tests fail, the log might get overwritten
        file.getAbsoluteFile().getParentFile().mkdirs();
        try (var fw = new FileWriter(file, false)) {
          fw.write(podLog);
        }
      } catch (Exception e) {
        Log.errorf("Error saving pod logs: %s", e.getMessage());
      }
    }
  }

  private static void setDefaultAwaitilityTimings() {
    Awaitility.setDefaultPollInterval(Duration.ofMillis(500));
    Awaitility.setDefaultTimeout(Duration.ofSeconds(360));
  }

  public void cleanup() {
    Log.info("Deleting Keycloak CR");
    k8sclient.resources(Keycloak.class).delete();
    Awaitility.await()
            .untilAsserted(() -> {
              var kcDeployments = k8sclient
                      .apps()
                      .statefulSets()
                      .inNamespace(namespace)
                      .withLabels(Constants.DEFAULT_LABELS)
                      .list()
                      .getItems();
              assertThat(kcDeployments.size()).isZero();
            });
  }

  @Override
  public void afterEach(QuarkusTestMethodContext context) {
      if (!(context.getTestInstance() instanceof BaseOperatorTest)) {
          return;
      }
      try {
          if (!context.getTestStatus().isTestFailed() || context.getTestStatus().getTestErrorCause() instanceof TestAbortedException) {
              return;
          }
          Log.warnf("Test failed with %s: %s", context.getTestStatus().getTestErrorCause().getMessage(), context.getTestStatus().getTestErrorCause().getClass().getName());
          savePodLogs();
          // provide some helpful entries in the main log as well
          k8sclient.resources(Keycloak.class).list().getItems().stream()
                  .filter(kc -> !Optional.ofNullable(kc.getStatus()).map(KeycloakStatus::isReady).orElse(false))
                  .forEach(kc -> {
                      Log.warnf("Keycloak failed to become ready \"%s\" %s", kc.getMetadata().getName(), Serialization.asYaml(kc.getStatus()));
                      var statefulSet = k8sclient.resources(StatefulSet.class).withName(KeycloakDeployment.getName(kc)).get();
                      if (statefulSet != null) {
                          Log.warnf("Keycloak \"%s\" StatefulSet status %s", kc.getMetadata().getName(), Serialization.asYaml(statefulSet.getStatus()));
                          k8sclient.pods().withLabels(statefulSet.getSpec().getSelector().getMatchLabels()).list()
                                  .getItems().stream().filter(pod -> !Readiness.isPodReady(pod)).forEach(pod -> {
                                      try {
                                          String log = k8sclient.pods().resource(pod).getLog();
                                          if (log.length() > 5000) {
                                              log = log.substring(log.length() - 5000);
                                          }
                                          Log.warnf("Not ready pod log \"%s\": %s", pod.getMetadata().getName(), log);
                                      } catch (KubernetesClientException e) {
                                          Log.warnf("No pod log for \"%s\": %s", pod.getMetadata().getName(), e.getMessage());
                                      }
                                  });
                      }
                  });
      } finally {
          cleanup();
      }
  }

  @AfterAll
  public static void after() throws FileNotFoundException {

    if (operatorDeployment == OperatorDeployment.local) {
      Log.info("Stopping Operator");
      operator.stop();

      Log.info("Creating new K8s Client");
      // create a new client bc operator has closed the old one
      createK8sClient();
    } else {
      cleanRBACresourcesAndOperatorDeployment();
    }

    Log.info("Deleting namespace : " + namespace);
    assertThat(k8sclient.namespaces().withName(namespace).delete()).isNotNull();
    k8sclient.close();
  }

  public static String getCurrentNamespace() {
    return namespace;
  }

  public static String getTestCustomImage() {
    return customImage;
  }

  /**
   * Get the default deployment modified/optimized by operator test settings
   * @param disableProbes when true the unsupported template will be used to effectively
   *   disable the probes, which will speed up testing for scenarios that don't interact
   *   with the underlying keycloak
   * @return
   */
  public static Keycloak getTestKeycloakDeployment(boolean disableProbes) {
      Keycloak kc = K8sUtils.getDefaultKeycloakDeployment();
      kc.getMetadata().setNamespace(getCurrentNamespace());
      String image = getTestCustomImage();
      if (image != null) {
          kc.getSpec().setImage(image);
      }
      if (disableProbes) {
          return disableProbes(kc);
      }
      return kc;
  }

  public static Keycloak disableProbes(Keycloak keycloak) {
      KeycloakSpecBuilder specBuilder = new KeycloakSpecBuilder(keycloak.getSpec());
      var podTemplateSpecBuilder = specBuilder.editOrNewUnsupported().editOrNewPodTemplate().editOrNewSpec();
      ContainersNested<SpecNested<PodTemplateNested<UnsupportedNested<KeycloakSpecBuilder>>>> containerBuilder = null;
      if (podTemplateSpecBuilder.hasContainers()) {
          containerBuilder = podTemplateSpecBuilder.editContainer(0);
      } else {
          containerBuilder = podTemplateSpecBuilder.addNewContainer();
      }
      keycloak.setSpec(containerBuilder.withNewLivenessProbe().withNewExec().addToCommand("true").endExec()
              .endLivenessProbe().withNewReadinessProbe().withNewExec().addToCommand("true").endExec()
              .endReadinessProbe().withNewStartupProbe().withNewExec().addToCommand("true").endExec()
              .endStartupProbe().endContainer().endSpec().endPodTemplate().endUnsupported().build());
      return keycloak;
  }

}
