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
package ai.langstream.assets;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.langstream.AbstractApplicationRunner;
import ai.langstream.api.model.AssetDefinition;
import ai.langstream.mockagents.MockAssetManagerCodeProvider;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.junit.jupiter.api.Test;

@Slf4j
class DeployAssetsTest extends AbstractApplicationRunner {

    @Test
    public void testDeployAsset() throws Exception {
        String tenant = "tenant";
        String[] expectedAgents = {"app-step1"};

        String secrets =
                """
                            secrets:
                              - id: "the-secret"
                                data:
                                   password: "bar"
                            """;
        Map<String, String> application =
                Map.of(
                        "configuration.yaml",
                        """
                            configuration:
                               resources:
                                    - type: "datasource"
                                      name: "the-resource"
                                      configuration:
                                         foo: "{{{secrets.the-secret.password}}}"
                            """,
                        "module.yaml",
                        """
                        module: "module-1"
                        id: "pipeline-1"
                        assets:
                          - name: "my-table"
                            creation-mode: create-if-not-exists
                            asset-type: "mock-database-resource"
                            config:
                                table: "{{{globals.table-name}}}"
                                datasource: "the-resource"
                        topics:
                          - name: "input-topic"
                            creation-mode: create-if-not-exists
                          - name: "output-topic"
                            creation-mode: create-if-not-exists
                        pipeline:
                          - name: "identity"
                            id: "step1"
                            type: "identity"
                            input: "input-topic"
                            output: "output-topic"
                        """);
        try (ApplicationRuntime applicationRuntime =
                deployApplicationWithSecrets(
                        tenant, "app", application, buildInstanceYaml(), secrets, expectedAgents)) {
            try (KafkaProducer<String, String> producer = createProducer();
                    KafkaConsumer<String, String> consumer = createConsumer("output-topic")) {
                sendMessage("input-topic", "test", producer);
                executeAgentRunners(applicationRuntime);
                waitForMessages(consumer, List.of("test"));
            }

            CopyOnWriteArrayList<AssetDefinition> deployedAssets =
                    MockAssetManagerCodeProvider.MockDatabaseResourceAssetManager.DEPLOYED_ASSETS;
            assertEquals(1, deployedAssets.size());
            AssetDefinition deployedAsset = deployedAssets.get(0);
            assertEquals("my-table", deployedAsset.getConfig().get("table"));
            Map<String, Object> datasource =
                    (Map<String, Object>) deployedAsset.getConfig().get("datasource");
            Map<String, Object> datasourceConfiguration =
                    (Map<String, Object>) datasource.get("configuration");
            assertEquals("bar", datasourceConfiguration.get("foo"));
        }
    }

    @Override
    protected String buildInstanceYaml() {
        return """
                instance:
                  globals:
                     table-name: "my-table"
                  streamingCluster:
                    type: "kafka"
                    configuration:
                      admin:
                        bootstrap.servers: "%s"
                  computeCluster:
                     type: "kubernetes"
                """
                .formatted(kafkaContainer.getBootstrapServers());
    }
}
