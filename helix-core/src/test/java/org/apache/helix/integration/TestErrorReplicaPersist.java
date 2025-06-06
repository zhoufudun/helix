package org.apache.helix.integration;

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

import java.util.Collections;
import java.util.Date;

import org.apache.helix.ConfigAccessor;
import org.apache.helix.HelixDataAccessor;
import org.apache.helix.HelixRollbackException;
import org.apache.helix.NotificationContext;
import org.apache.helix.TestHelper;
import org.apache.helix.controller.rebalancer.strategy.CrushEdRebalanceStrategy;
import org.apache.helix.integration.common.ZkStandAloneCMTestBase;
import org.apache.helix.integration.manager.ClusterControllerManager;
import org.apache.helix.integration.manager.MockParticipantManager;
import org.apache.helix.integration.rebalancer.TestAutoRebalance;
import org.apache.helix.manager.zk.ZKHelixDataAccessor;
import org.apache.helix.manager.zk.ZkBaseDataAccessor;
import org.apache.helix.model.ClusterConfig;
import org.apache.helix.model.ExternalView;
import org.apache.helix.model.IdealState;
import org.apache.helix.model.MasterSlaveSMD;
import org.apache.helix.model.Message;
import org.apache.helix.participant.StateMachineEngine;
import org.apache.helix.participant.statemachine.StateModel;
import org.apache.helix.participant.statemachine.StateModelFactory;
import org.apache.helix.participant.statemachine.StateModelInfo;
import org.apache.helix.participant.statemachine.Transition;
import org.apache.helix.tools.ClusterStateVerifier;
import org.apache.helix.tools.ClusterVerifiers.BestPossibleExternalViewVerifier;
import org.apache.helix.tools.ClusterVerifiers.HelixClusterVerifier;
import org.apache.helix.zookeeper.datamodel.ZNRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

public class TestErrorReplicaPersist extends ZkStandAloneCMTestBase {
  @BeforeClass
  public void beforeClass() throws Exception {
    // Logger.getRootLogger().setLevel(Level.INFO);
    System.out.println("START " + CLASS_NAME + " at " + new Date(System.currentTimeMillis()));

    int numNode = NODE_NR + 1;
    _participants = new MockParticipantManager[numNode];
    // setup storage cluster
    _gSetupTool.addCluster(CLUSTER_NAME, true);
    createResourceWithDelayedRebalance(CLUSTER_NAME, TEST_DB, MasterSlaveSMD.name, _PARTITIONS,
        _replica, _replica - 1, 1800000, CrushEdRebalanceStrategy.class.getName());

    // Create customized resource
    _gSetupTool.addResourceToCluster(CLUSTER_NAME, "db-customized", 64, MasterSlaveSMD.name,
        IdealState.RebalanceMode.CUSTOMIZED.name());

    for (int i = 0; i < numNode; i++) {
      String storageNodeName = PARTICIPANT_PREFIX + "_" + (START_PORT + i);
      _gSetupTool.addInstanceToCluster(CLUSTER_NAME, storageNodeName);
    }

    _gSetupTool.rebalanceStorageCluster(CLUSTER_NAME, TEST_DB, _replica);

    // start dummy participants
    for (int i = 0; i < numNode; i++) {
      String instanceName = PARTICIPANT_PREFIX + "_" + (START_PORT + i);
      MockParticipantManager participant =
          new MockParticipantManager(ZK_ADDR, CLUSTER_NAME, instanceName);
      participant.syncStart();
      _participants[i] = participant;
    }
    enablePersistBestPossibleAssignment(_gZkClient, CLUSTER_NAME, true);
    enableDelayRebalanceInCluster(_gZkClient, CLUSTER_NAME, true, 1800000);

    // start controller
    String controllerName = CONTROLLER_PREFIX + "_0";
    _controller = new ClusterControllerManager(ZK_ADDR, CLUSTER_NAME, controllerName);
    _controller.syncStart();

    _gSetupTool.rebalanceStorageCluster(CLUSTER_NAME, "db-customized", _replica);

    boolean result = ClusterStateVerifier.verifyByZkCallback(
        new TestAutoRebalance.ExternalViewBalancedVerifier(_gZkClient, CLUSTER_NAME, TEST_DB));

    Assert.assertTrue(result);
  }

  @AfterClass
  public void afterClass() throws Exception {
    for (MockParticipantManager participant : _participants) {
      participant.syncStop();
    }
    super.afterClass();
  }

  @Test
  public void testErrorReplicaPersist() throws InterruptedException {
    ConfigAccessor configAccessor = new ConfigAccessor(_gZkClient);
    ClusterConfig clusterConfig = configAccessor.getClusterConfig(CLUSTER_NAME);
    clusterConfig.setErrorPartitionThresholdForLoadBalance(100000);
    configAccessor.setClusterConfig(CLUSTER_NAME, clusterConfig);

    for (int i = 0; i < (NODE_NR + 1) / 2; i++) {
      _participants[i].syncStop();
      Thread.sleep(2000);
      String instanceName = PARTICIPANT_PREFIX + "_" + (START_PORT + i);
      MockParticipantManager participant =
          new MockParticipantManager(ZK_ADDR, CLUSTER_NAME, instanceName);
      StateMachineEngine stateMachineEngine = participant.getStateMachineEngine();
      stateMachineEngine
          .registerStateModelFactory(MasterSlaveSMD.name, new MockFailedMSStateModelFactory());
      participant.syncStart();
      _participants[i] = participant;
    }
    // verify full auto converged
    HelixClusterVerifier verifier =
        new BestPossibleExternalViewVerifier.Builder(CLUSTER_NAME).setZkClient(_gZkClient).setResources(
                Collections.singleton(TEST_DB))
            .setWaitTillVerify(TestHelper.DEFAULT_REBALANCE_PROCESSING_WAIT_TIME)
            .build();
    Assert.assertTrue(((BestPossibleExternalViewVerifier) verifier).verifyByPolling());
    for (int i = 0; i < (NODE_NR + 1) / 2; i++) {
      _gSetupTool.getClusterManagementTool()
          .enableInstance(CLUSTER_NAME, _participants[i].getInstanceName(), false);
    }

    Assert.assertTrue(((BestPossibleExternalViewVerifier) verifier).verifyByPolling());

    // verify customized external view has 3 replica for each partition
    HelixDataAccessor accessor =
        new ZKHelixDataAccessor(CLUSTER_NAME, new ZkBaseDataAccessor<ZNRecord>(_gZkClient));
    ExternalView externalView =
        accessor
            .getProperty( accessor.keyBuilder().externalView("db-customized"));
    Assert.assertEquals(externalView.getRecord().getMapFields().size(), 64);
    for(String partition : externalView.getRecord().getMapFields().keySet()) {
      Assert.assertEquals(externalView.getRecord().getMapField(partition).size(), 3);
    }
  }


  class MockFailedMSStateModelFactory
      extends StateModelFactory<MockFailedMSStateModel> {

    @Override
    public MockFailedMSStateModel createNewStateModel(String resourceName,
        String partitionKey) {
      MockFailedMSStateModel model = new MockFailedMSStateModel();
      return model;
    }
  }

  @StateModelInfo(initialState = "OFFLINE", states = { "MASTER", "SLAVE", "ERROR"
  })
  public static class MockFailedMSStateModel extends StateModel {
    private static Logger LOG = LoggerFactory.getLogger(MockFailedMSStateModel.class);

    public MockFailedMSStateModel() {
    }

    @Transition(to = "SLAVE", from = "OFFLINE") public void onBecomeSlaveFromOffline(
        Message message, NotificationContext context) throws IllegalAccessException {
      throw new IllegalAccessException("Failed!");
    }

    @Transition(to = "MASTER", from = "SLAVE") public void onBecomeMasterFromSlave(Message message,
        NotificationContext context) throws InterruptedException, HelixRollbackException {
      LOG.error("Become MASTER from SLAVE");
    }

    @Transition(to = "SLAVE", from = "MASTER") public void onBecomeSlaveFromMaster(Message message,
        NotificationContext context) {
      LOG.info("Become Slave from Master");
    }

    @Transition(to = "OFFLINE", from = "SLAVE") public void onBecomeOfflineFromSlave(
        Message message, NotificationContext context) {
      LOG.info("Become OFFLINE from SLAVE");
    }

    @Transition(to = "DROPPED", from = "OFFLINE") public void onBecomeDroppedFromOffline(
        Message message, NotificationContext context) {
      LOG.info("Become DROPPED FROM OFFLINE");
    }
  }

}
