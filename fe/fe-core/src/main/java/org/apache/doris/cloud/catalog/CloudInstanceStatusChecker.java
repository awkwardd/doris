// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.cloud.catalog;

import org.apache.doris.catalog.Env;
import org.apache.doris.cloud.proto.Cloud;
import org.apache.doris.common.Config;
import org.apache.doris.common.util.MasterDaemon;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class CloudInstanceStatusChecker extends MasterDaemon {
    private static final Logger LOG = LogManager.getLogger(CloudInstanceStatusChecker.class);

    public CloudInstanceStatusChecker() {
        super("cloud instance check");
    }

    @Override
    protected void runAfterCatalogReady() {
        try {
            Cloud.GetInstanceResponse response =
                    Env.getCurrentSystemInfo().getCloudInstance();
            LOG.debug("get from ms response {}", response);
            if (!response.hasStatus() || !response.getStatus().hasCode()
                    || response.getStatus().getCode() != Cloud.MetaServiceCode.OK) {
                LOG.warn("failed to get cloud instance due to incomplete response, "
                        + "cloud_unique_id={}, response={}", Config.cloud_unique_id, response);
            } else {
                Env.getCurrentSystemInfo().setInstanceStatus(response.getInstance().getStatus());
            }
        } catch (Exception e) {
            LOG.warn("get instance from ms exception", e);
        }
    }
}
