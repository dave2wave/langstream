/*
 * Copyright DataStax, Inc.
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
package ai.langstream.runtime.tester;

import ai.langstream.api.model.Application;
import ai.langstream.api.runner.topics.TopicConnectionsRuntimeRegistry;
import ai.langstream.api.runtime.ClusterRuntimeRegistry;
import ai.langstream.api.runtime.ExecutionPlan;
import ai.langstream.api.runtime.PluginsRegistry;
import ai.langstream.deployer.k8s.agents.AgentResourcesFactory;
import ai.langstream.impl.deploy.ApplicationDeployer;
import ai.langstream.impl.parser.ModelBuilder;
import ai.langstream.runtime.agent.AgentRunner;
import ai.langstream.runtime.agent.api.AgentInfo;
import ai.langstream.runtime.agent.nar.NarFileHandler;
import ai.langstream.runtime.api.agent.RuntimePodConfiguration;
import io.fabric8.kubernetes.api.model.Secret;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LocalApplicationRunner implements AutoCloseable {

    final KubeTestServer kubeServer = new KubeTestServer();
    final ApplicationDeployer applicationDeployer;
    final NarFileHandler narFileHandler;

    final Path agentsDirectory;

    final AtomicBoolean continueLoop = new AtomicBoolean(true);

    final CountDownLatch exited = new CountDownLatch(1);

    final AtomicBoolean started = new AtomicBoolean();

    public LocalApplicationRunner(Path agentsDirectory) throws Exception {
        this.agentsDirectory = agentsDirectory;
        this.narFileHandler =
                new NarFileHandler(
                        agentsDirectory, List.of(), Thread.currentThread().getContextClassLoader());
        TopicConnectionsRuntimeRegistry topicConnectionsRuntimeRegistry =
                new TopicConnectionsRuntimeRegistry();
        narFileHandler.scan();
        topicConnectionsRuntimeRegistry.setPackageLoader(narFileHandler);
        this.applicationDeployer =
                ApplicationDeployer.builder()
                        .registry(new ClusterRuntimeRegistry())
                        .pluginsRegistry(new PluginsRegistry())
                        .topicConnectionsRuntimeRegistry(topicConnectionsRuntimeRegistry)
                        .build();
    }

    protected record ApplicationRuntime(
            String tenant,
            String applicationId,
            Application applicationInstance,
            ExecutionPlan implementation,
            Map<String, Secret> secrets,
            ApplicationDeployer applicationDeployer)
            implements AutoCloseable {

        public <T> T getGlobal(String key) {
            return (T) implementation.getApplication().getInstance().globals().get(key);
        }

        public void close() {
            applicationDeployer.delete(tenant, implementation, null);
        }
    }

    public ApplicationRuntime deployApplicationWithSecrets(
            String tenant,
            String appId,
            ModelBuilder.ApplicationWithPackageInfo applicationWithPackageInfo,
            String... expectedAgents)
            throws Exception {

        kubeServer.spyAgentCustomResources(tenant, expectedAgents);
        final Map<String, Secret> secrets =
                kubeServer.spyAgentCustomResourcesSecrets(tenant, expectedAgents);

        Application applicationInstance = applicationWithPackageInfo.getApplication();

        ExecutionPlan implementation =
                applicationDeployer.createImplementation(appId, applicationInstance);

        applicationDeployer.deploy(tenant, implementation, null);

        return new ApplicationRuntime(
                tenant, appId, applicationInstance, implementation, secrets, applicationDeployer);
    }

    public record AgentRunResult(Map<String, AgentInfo> info) {}

    public void start() {
        kubeServer.start();
    }

    public AgentRunResult executeAgentRunners(ApplicationRuntime runtime) throws Exception {

        String runnerExecutionId = UUID.randomUUID().toString();
        log.info(
                "{} Starting Agent Runners. Running {} pods",
                runnerExecutionId,
                runtime.secrets.size());
        Map<String, AgentInfo> allAgentsInfo = new ConcurrentHashMap<>();
        started.set(true);
        try {
            List<RuntimePodConfiguration> pods = new ArrayList<>();
            runtime.secrets()
                    .forEach(
                            (key, secret) -> {
                                RuntimePodConfiguration runtimePodConfiguration =
                                        AgentResourcesFactory.readRuntimePodConfigurationFromSecret(
                                                secret);
                                log.info(
                                        "{} Pod configuration {} = {}",
                                        runnerExecutionId,
                                        key,
                                        runtimePodConfiguration);
                                pods.add(runtimePodConfiguration);
                            });
            // execute all the pods
            ExecutorService executorService = Executors.newCachedThreadPool();
            List<CompletableFuture> futures = new ArrayList<>();
            for (RuntimePodConfiguration podConfiguration : pods) {
                CompletableFuture<?> handle = new CompletableFuture<>();
                futures.add(handle);
                executorService.submit(
                        () -> {
                            String originalName = Thread.currentThread().getName();
                            Thread.currentThread()
                                    .setName(
                                            podConfiguration.agent().agentId()
                                                    + "runner-tid-"
                                                    + runnerExecutionId);
                            try {
                                log.info(
                                        "{} AgentPod {} Started",
                                        runnerExecutionId,
                                        podConfiguration.agent().agentId());
                                AgentInfo agentInfo = new AgentInfo();
                                allAgentsInfo.put(podConfiguration.agent().agentId(), agentInfo);
                                AtomicInteger numLoops = new AtomicInteger();
                                AgentRunner.runAgent(
                                        podConfiguration,
                                        null,
                                        null,
                                        agentsDirectory,
                                        agentInfo,
                                        continueLoop::get,
                                        () -> {},
                                        false);
                                List<?> infos = agentInfo.serveWorkerStatus();
                                log.info(
                                        "{} AgentPod {} AgentInfo {}",
                                        runnerExecutionId,
                                        podConfiguration.agent().agentId(),
                                        infos);
                                handle.complete(null);
                            } catch (Throwable error) {
                                log.error(
                                        "{} Error on AgentPod {}",
                                        runnerExecutionId,
                                        podConfiguration.agent().agentId(),
                                        error);
                                handle.completeExceptionally(error);
                            } finally {
                                log.info(
                                        "{} AgentPod {} finished",
                                        runnerExecutionId,
                                        podConfiguration.agent().agentId());
                                Thread.currentThread().setName(originalName);
                            }
                        });
            }
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
            } catch (ExecutionException executionException) {
                log.error(
                        "Some error occurred while executing the agent",
                        executionException.getCause());
                // unwrap the exception in order to easily perform assertions
                if (executionException.getCause() instanceof Exception) {
                    throw (Exception) executionException.getCause();
                } else {
                    throw executionException;
                }
            }
            executorService.shutdown();
            executorService.awaitTermination(1, TimeUnit.MINUTES);
        } finally {
            log.info("{} Agent Runners Stopped", runnerExecutionId);
            exited.countDown();
        }
        return new AgentRunResult(allAgentsInfo);
    }

    public void close() {
        continueLoop.set(false);

        if (started.get()) {
            try {
                exited.await(1, TimeUnit.MINUTES);
            } catch (InterruptedException ok) {
            }
        }
        if (applicationDeployer != null) {
            // this closes the kubernetes client
            applicationDeployer.close();
        }
        if (narFileHandler != null) {
            narFileHandler.close();
        }
    }
}
