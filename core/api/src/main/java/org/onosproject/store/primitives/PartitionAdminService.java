/*
 * Copyright 2016 Open Networking Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onosproject.store.primitives;

import java.util.concurrent.CompletableFuture;

import org.onosproject.cluster.PartitionId;

/**
 * Administrative interface for partition membership changes.
 */
public interface PartitionAdminService {

    /**
     * Leaves a partition.
     *
     * @param partitionId partition identifier
     * @return future that is completed when the operation completes.
     */
    CompletableFuture<Void> leave(PartitionId partitionId);

    /**
     * Joins a partition.
     *
     * @param partitionId partition identifier
     * @return future that is completed when the operation completes.
     */
    CompletableFuture<Void> join(PartitionId partitionId);
}