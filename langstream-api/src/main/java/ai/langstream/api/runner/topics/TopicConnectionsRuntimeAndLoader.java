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
package ai.langstream.api.runner.topics;

import ai.langstream.api.model.StreamingCluster;
import ai.langstream.api.runtime.ExecutionPlan;
import java.util.Map;

public record TopicConnectionsRuntimeAndLoader(
        TopicConnectionsRuntime connectionsRuntime, ClassLoader classLoader) {

    public void executeWithContextClassloader(RunnableWithException code) throws Exception {
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(classLoader);
            code.run(connectionsRuntime);
        } finally {
            Thread.currentThread().setContextClassLoader(contextClassLoader);
        }
    }

    public void executeNoExceptionWithContextClassloader(RunnableNoException code) {
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(classLoader);
            code.run(connectionsRuntime);
        } finally {
            Thread.currentThread().setContextClassLoader(contextClassLoader);
        }
    }

    public <T> T callWithContextClassloader(CallableWithException code) throws Exception {
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(classLoader);
            return (T) code.call(connectionsRuntime);
        } finally {
            Thread.currentThread().setContextClassLoader(contextClassLoader);
        }
    }

    public <T> T callNoExceptionWithContextClassloader(CallableNoException code) {
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(classLoader);
            return (T) code.call(connectionsRuntime);
        } finally {
            Thread.currentThread().setContextClassLoader(contextClassLoader);
        }
    }

    public TopicConnectionsRuntime asTopicConnectionsRuntime() {

        return new TopicConnectionsRuntime() {

            @Override
            public void init(StreamingCluster streamingCluster) {
                executeNoExceptionWithContextClassloader(code -> code.init(streamingCluster));
            }

            @Override
            public void deploy(ExecutionPlan applicationInstance) {
                executeNoExceptionWithContextClassloader(code -> code.deploy(applicationInstance));
            }

            @Override
            public void delete(ExecutionPlan applicationInstance) {
                executeNoExceptionWithContextClassloader(code -> code.delete(applicationInstance));
            }

            @Override
            public void close() {
                executeNoExceptionWithContextClassloader(TopicConnectionsRuntime::close);
            }

            @Override
            public TopicConsumer createConsumer(
                    String agentId,
                    StreamingCluster streamingCluster,
                    Map<String, Object> configuration) {
                return callNoExceptionWithContextClassloader(
                        code -> code.createConsumer(agentId, streamingCluster, configuration));
            }

            @Override
            public TopicReader createReader(
                    StreamingCluster streamingCluster,
                    Map<String, Object> configuration,
                    TopicOffsetPosition initialPosition) {
                return callNoExceptionWithContextClassloader(
                        code ->
                                code.createReader(
                                        streamingCluster, configuration, initialPosition));
            }

            @Override
            public TopicProducer createProducer(
                    String agentId,
                    StreamingCluster streamingCluster,
                    Map<String, Object> configuration) {
                return callNoExceptionWithContextClassloader(
                        code -> code.createProducer(agentId, streamingCluster, configuration));
            }

            @Override
            public TopicProducer createDeadletterTopicProducer(
                    String agentId,
                    StreamingCluster streamingCluster,
                    Map<String, Object> configuration) {
                return callNoExceptionWithContextClassloader(
                        code ->
                                code.createDeadletterTopicProducer(
                                        agentId, streamingCluster, configuration));
            }

            @Override
            public TopicAdmin createTopicAdmin(
                    String agentId,
                    StreamingCluster streamingCluster,
                    Map<String, Object> configuration) {
                return callNoExceptionWithContextClassloader(
                        code -> code.createTopicAdmin(agentId, streamingCluster, configuration));
            }
        };
    }

    public void close() throws Exception {
        executeWithContextClassloader(TopicConnectionsRuntime::close);
    }

    @FunctionalInterface
    public interface RunnableWithException {
        void run(TopicConnectionsRuntime agent) throws Exception;
    }

    @FunctionalInterface
    public interface RunnableNoException {
        void run(TopicConnectionsRuntime agent);
    }

    @FunctionalInterface
    public interface CallableWithException {
        Object call(TopicConnectionsRuntime agent) throws Exception;
    }

    @FunctionalInterface
    public interface CallableNoException {
        Object call(TopicConnectionsRuntime agent);
    }
}
