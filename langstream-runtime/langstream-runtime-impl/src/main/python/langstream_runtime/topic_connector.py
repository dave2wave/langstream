#
# Copyright DataStax, Inc.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

import logging
from typing import List, Dict, Any

from .api import (
    Source,
    Record,
    RecordType,
    Sink,
    CommitCallback,
    TopicProducer,
    TopicConsumer,
)

LOG = logging.getLogger(__name__)


class TopicConsumerSource(Source):
    def __init__(self, consumer: TopicConsumer):
        self.consumer = consumer

    def read(self) -> List[RecordType]:
        return self.consumer.read()

    def commit(self, records: List[Record]):
        self.consumer.commit(records)

    def start(self):
        LOG.info(f"Starting consumer {self.consumer}")
        self.consumer.start()

    def close(self):
        LOG.info(f"Closing consumer {self.consumer}")
        self.consumer.close()

    def agent_info(self) -> Dict[str, Any]:
        return self.consumer.get_info()

    def __str__(self):
        return f"TopicConsumerSource{{consumer={self.consumer}}}"


class TopicConsumerWithDLQSource(TopicConsumerSource):
    def __init__(self, consumer: TopicConsumer, dlq_producer: TopicProducer):
        super().__init__(consumer)
        self.dlq_producer = dlq_producer

    def start(self):
        super().start()
        self.dlq_producer.start()

    def close(self):
        super().close()
        self.dlq_producer.close()

    def permanent_failure(self, record: Record, error: Exception):
        LOG.error(f"Sending record to DLQ: {record}")
        self.dlq_producer.write([record])


class TopicProducerSink(Sink):
    def __init__(self, producer: TopicProducer):
        self.producer = producer
        self.commit_callback = None

    def start(self):
        LOG.info(f"Starting producer {self.producer}")
        self.producer.start()

    def close(self):
        LOG.info(f"Closing producer {self.producer}")
        self.producer.close()

    def write(self, records: List[Record]):
        self.producer.write(records)
        self.commit_callback.commit(records)

    def set_commit_callback(self, commit_callback: CommitCallback):
        self.commit_callback = commit_callback

    def agent_info(self) -> Dict[str, Any]:
        return self.producer.get_info()

    def __str__(self):
        return f"TopicProducerSink{{producer={self.producer}}}"
