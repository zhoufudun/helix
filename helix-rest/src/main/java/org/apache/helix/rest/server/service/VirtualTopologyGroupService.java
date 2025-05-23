package org.apache.helix.rest.server.service;

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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.apache.helix.AccessOption;
import org.apache.helix.ConfigAccessor;
import org.apache.helix.HelixAdmin;
import org.apache.helix.HelixDataAccessor;
import org.apache.helix.HelixException;
import org.apache.helix.PropertyPathBuilder;
import org.apache.helix.cloud.constants.VirtualTopologyGroupConstants;
import org.apache.helix.cloud.topology.FaultZoneBasedVirtualGroupAssignmentAlgorithm;
import org.apache.helix.cloud.topology.FifoVirtualGroupAssignmentAlgorithm;
import org.apache.helix.cloud.topology.VirtualGroupAssignmentAlgorithm;
import org.apache.helix.model.ClusterConfig;
import org.apache.helix.model.ClusterTopologyConfig;
import org.apache.helix.model.InstanceConfig;
import org.apache.helix.rest.server.json.cluster.ClusterTopology;
import org.apache.helix.zookeeper.datamodel.ZNRecord;
import org.apache.helix.zookeeper.zkclient.DataUpdater;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.helix.cloud.constants.VirtualTopologyGroupConstants.PATH_NAME_SPLITTER;
import static org.apache.helix.util.VirtualTopologyUtil.computeVirtualFaultZoneTypeKey;

/**
 * Service for virtual topology group.
 * It's a virtualization layer on top of physical fault domain and topology in cloud environments.
 * The service computes the mapping from virtual group to instances based on the current cluster topology and update the
 * information to cluster and all instances in the cluster.
 */
public class VirtualTopologyGroupService {
  private static final Logger LOG = LoggerFactory.getLogger(VirtualTopologyGroupService.class);

  private final HelixAdmin _helixAdmin;
  private final ClusterService _clusterService;
  private final ConfigAccessor _configAccessor;
  private final HelixDataAccessor _dataAccessor;
  private VirtualGroupAssignmentAlgorithm _assignmentAlgorithm;

  public VirtualTopologyGroupService(HelixAdmin helixAdmin, ClusterService clusterService,
      ConfigAccessor configAccessor, HelixDataAccessor dataAccessor) {
    _helixAdmin = helixAdmin;
    _clusterService = clusterService;
    _configAccessor = configAccessor;
    _dataAccessor = dataAccessor;
    _assignmentAlgorithm = FifoVirtualGroupAssignmentAlgorithm.getInstance(); // default assignment algorithm
  }

  /**
   * Add virtual topology group for a cluster.
   * This includes calculating the virtual group assignment for all instances in the cluster then update instance config
   * and cluster config. We override {@link ClusterConfig.ClusterConfigProperty#TOPOLOGY} and
   * {@link ClusterConfig.ClusterConfigProperty#FAULT_ZONE_TYPE} for cluster config, and add new field to
   * {@link InstanceConfig.InstanceConfigProperty#DOMAIN} that contains virtual topology group information.
   * This is only supported for cloud environments. Cluster is expected to be in maintenance mode during config change.
   * @param clusterName the cluster name.
   * @param customFields custom fields, {@link VirtualTopologyGroupConstants#GROUP_NAME}
   *                     and {@link VirtualTopologyGroupConstants#GROUP_NUMBER} are required,
   *                     {@link VirtualTopologyGroupConstants#AUTO_MAINTENANCE_MODE_DISABLED} is optional.
   *                     -- if set ture, the cluster will NOT automatically enter/exit maintenance mode during this API call;
   *                     -- if set false or not set, the cluster will automatically enter maintenance mode and exit after
   *                     the call succeeds. It won't proceed if the cluster is already in maintenance mode.
   *                     Either case, the cluster must be in maintenance mode before config change.
   *                     {@link VirtualTopologyGroupConstants#ASSIGNMENT_ALGORITHM_TYPE} is optional, default to INSTANCE_BASED.
   *                     {@link VirtualTopologyGroupConstants#FORCE_RECOMPUTE} is optional, default to false.
   *                     -- if set true, the virtual topology group will be recomputed from scratch by ignoring the existing
   *                     virtual topology group information.
   *                     -- if set false or not set, the virtual topology group will be incrementally computed based on the
   *                     existing virtual topology group information if possible.
   */
  public void addVirtualTopologyGroup(String clusterName, Map<String, String> customFields) {
    // validation
    ClusterConfig clusterConfig = _configAccessor.getClusterConfig(clusterName);
    // Collect the real topology of the cluster and the virtual topology of the cluster
    ClusterTopology clusterTopology = _clusterService.getTopologyOfVirtualCluster(clusterName, true);
    // If forceRecompute is set to true, we will recompute the virtual topology group from scratch
    // by ignoring the existing virtual topology group information.
    String forceRecompute = customFields.getOrDefault(VirtualTopologyGroupConstants.FORCE_RECOMPUTE, "false");
    boolean forceRecomputeFlag = Boolean.parseBoolean(forceRecompute);
    ClusterTopology virtualTopology =
        forceRecomputeFlag ? new ClusterTopology(clusterName, Collections.emptyList(),
            Collections.emptySet())
            : _clusterService.getTopologyOfVirtualCluster(clusterName, false);

    Preconditions.checkState(clusterConfig.isTopologyAwareEnabled(),
        "Topology-aware rebalance is not enabled in cluster " + clusterName);
    String groupName = customFields.get(VirtualTopologyGroupConstants.GROUP_NAME);
    String groupNumberStr = customFields.get(VirtualTopologyGroupConstants.GROUP_NUMBER);
    Preconditions.checkArgument(!StringUtils.isEmpty(groupName), "virtualTopologyGroupName cannot be empty!");
    Preconditions.checkArgument(!StringUtils.isEmpty(groupNumberStr), "virtualTopologyGroupNumber cannot be empty!");
    int numGroups = 0;
    try {
      numGroups = Integer.parseInt(groupNumberStr);
      Preconditions.checkArgument(numGroups > 0, "Number of virtual groups should be positive.");
    } catch (NumberFormatException ex) {
      throw new IllegalArgumentException("virtualTopologyGroupNumber " + groupNumberStr + " is not an integer.", ex);
    }

    String algorithm = customFields.get(VirtualTopologyGroupConstants.ASSIGNMENT_ALGORITHM_TYPE);
    algorithm = algorithm == null ? VirtualTopologyGroupConstants.VirtualGroupAssignmentAlgorithm.INSTANCE_BASED.toString() : algorithm;
    if (algorithm != null) {
      VirtualTopologyGroupConstants.VirtualGroupAssignmentAlgorithm algorithmEnum = null;
      try {
        algorithmEnum =
            VirtualTopologyGroupConstants.VirtualGroupAssignmentAlgorithm.valueOf(algorithm);
      } catch (Exception e) {
        throw new IllegalArgumentException(
            "Failed to instantiate assignment algorithm " + algorithm, e);
      }
      switch (algorithmEnum) {
        case ZONE_BASED:
          Preconditions.checkArgument(numGroups <= clusterTopology.getZones().size(),
              "Number of virtual groups cannot be greater than the number of zones.");
          _assignmentAlgorithm = FaultZoneBasedVirtualGroupAssignmentAlgorithm.getInstance();
          break;
        case INSTANCE_BASED:
          Preconditions.checkArgument(numGroups <= clusterTopology.getAllInstances().size(),
              "Number of virtual groups cannot be greater than the number of instances.");
          _assignmentAlgorithm = FifoVirtualGroupAssignmentAlgorithm.getInstance();
          break;
        default:
          throw new IllegalArgumentException("Unsupported assignment algorithm " + algorithm);
      }
    }
    LOG.info("Computing virtual topology group for cluster {} with param {}", clusterName, customFields);

    // compute group assignment
    Map<String, Set<String>> assignment =
        _assignmentAlgorithm.computeAssignment(numGroups, groupName,
            clusterTopology.toZoneMapping(), virtualTopology.toZoneMapping());

    boolean autoMaintenanceModeDisabled = Boolean.parseBoolean(
        customFields.getOrDefault(VirtualTopologyGroupConstants.AUTO_MAINTENANCE_MODE_DISABLED, "false"));
    // if auto mode is NOT disabled, let service enter maintenance mode and exit after the API succeeds.
    if (!autoMaintenanceModeDisabled) {
      Preconditions.checkState(!_helixAdmin.isInMaintenanceMode(clusterName),
          "This operation is not allowed if cluster is already in maintenance mode before the API call. "
              + "Please set autoMaintenanceModeDisabled=true if this is intended.");
      _helixAdmin.manuallyEnableMaintenanceMode(clusterName, true,
          "Enable maintenanceMode for virtual topology group change.", customFields);
    }
    Preconditions.checkState(_helixAdmin.isInMaintenanceMode(clusterName),
        "Cluster is not in maintenance mode. This is required for virtual topology group setting. "
            + "Please set autoMaintenanceModeDisabled=false (default) to let the cluster enter maintenance mode automatically, "
            + "or use autoMaintenanceModeDisabled=true and control cluster maintenance mode in client side.");

    updateConfigs(clusterName, clusterConfig, assignment);
    if (!autoMaintenanceModeDisabled) {
      _helixAdmin.manuallyEnableMaintenanceMode(clusterName, false,
          "Disable maintenanceMode after virtual topology group change.", customFields);
    }
  }

  private void updateConfigs(String clusterName, ClusterConfig clusterConfig, Map<String, Set<String>> assignment) {
    List<String> zkPaths = new ArrayList<>();
    List<DataUpdater<ZNRecord>> updaters = new ArrayList<>();
    createInstanceConfigUpdater(clusterConfig, assignment).forEach((zkPath, updater) -> {
      zkPaths.add(zkPath);
      updaters.add(updater);
    });
    // update instance config
    boolean[] results = _dataAccessor.updateChildren(zkPaths, updaters, AccessOption.EPHEMERAL);
    for (int i = 0; i < results.length; i++) {
      if (!results[i]) {
        throw new HelixException("Failed to update instance config for path " + zkPaths.get(i));
      }
    }
    // update cluster config
    String virtualTopologyString = computeVirtualTopologyString(clusterConfig);
    clusterConfig.setTopology(virtualTopologyString);
    clusterConfig.setFaultZoneType(computeVirtualFaultZoneTypeKey(clusterConfig.getFaultZoneType()));
    _configAccessor.updateClusterConfig(clusterName, clusterConfig);
    LOG.info("Successfully update instance and cluster config for {}", clusterName);
  }

  /**
   * Compute the virtual topology string based on the cluster config by replacing the old fault zone
   * with the new virtual topology fault zone key.
   *
   * @param clusterConfig cluster config for the cluster.
   * @return the updated virtual topology string.
   */
  @VisibleForTesting
  static String computeVirtualTopologyString(ClusterConfig clusterConfig) {
    ClusterTopologyConfig clusterTopologyConfig =
        ClusterTopologyConfig.createFromClusterConfig(clusterConfig);
    String topologyString = clusterConfig.getTopology();

    if (topologyString == null || topologyString.isEmpty()) {
      throw new IllegalArgumentException("Topology string cannot be null or empty");
    }

    String newFaultZone = computeVirtualFaultZoneTypeKey(clusterConfig.getFaultZoneType());
    String[] newTopologyString = topologyString.split(PATH_NAME_SPLITTER);
    for (int i = 0; i < newTopologyString.length; i++) {
      if (newTopologyString[i].equals(clusterTopologyConfig.getFaultZoneType())) {
        newTopologyString[i] = newFaultZone;
      }
    }
    return String.join(PATH_NAME_SPLITTER, newTopologyString);
  }

  /**
   * Create updater for instance config for async update.
   * @param clusterConfig cluster config for the cluster which the instance reside.
   * @param assignment virtual group assignment.
   * @return a map from instance zkPath to its {@link DataUpdater} to update.
   */
  @VisibleForTesting
  static Map<String, DataUpdater<ZNRecord>> createInstanceConfigUpdater(
      ClusterConfig clusterConfig, Map<String, Set<String>> assignment) {
    Map<String, DataUpdater<ZNRecord>> updaters = new HashMap<>();
    for (Map.Entry<String, Set<String>> entry : assignment.entrySet()) {
      String virtualGroup = entry.getKey();
      for (String instanceName : entry.getValue()) {
        String path = PropertyPathBuilder.instanceConfig(clusterConfig.getClusterName(), instanceName);
        updaters.put(path, currentData -> {
          InstanceConfig instanceConfig = new InstanceConfig(currentData);
          Map<String, String> domainMap = instanceConfig.getDomainAsMap();
          domainMap.put(computeVirtualFaultZoneTypeKey(clusterConfig.getFaultZoneType()), virtualGroup);
          instanceConfig.setDomain(domainMap);
          return instanceConfig.getRecord();
        });
      }
    }
    return updaters;
  }
}
