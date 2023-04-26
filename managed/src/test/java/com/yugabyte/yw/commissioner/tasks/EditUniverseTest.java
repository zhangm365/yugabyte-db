// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.commissioner.tasks;

import static com.yugabyte.yw.forms.UniverseConfigureTaskParams.ClusterOperationType.EDIT;
import static com.yugabyte.yw.models.TaskInfo.State.Failure;
import static com.yugabyte.yw.models.TaskInfo.State.Success;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.yugabyte.yw.commissioner.Common;
import com.yugabyte.yw.common.PlacementInfoUtil;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams.Cluster;
import com.yugabyte.yw.models.AvailabilityZone;
import com.yugabyte.yw.models.NodeInstance;
import com.yugabyte.yw.models.TaskInfo;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.helpers.NodeDetails;
import com.yugabyte.yw.models.helpers.PlacementInfo;
import com.yugabyte.yw.models.helpers.TaskType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;
import org.yb.client.ChangeConfigResponse;
import org.yb.client.ChangeMasterClusterConfigResponse;
import org.yb.client.GetMasterClusterConfigResponse;
import org.yb.client.ListMastersResponse;
import org.yb.client.ListTabletServersResponse;
import org.yb.master.CatalogEntityInfo;
import play.libs.Json;

@RunWith(MockitoJUnitRunner.class)
public class EditUniverseTest extends UniverseModifyBaseTest {

  private static final List<TaskType> UNIVERSE_EXPAND_TASK_SEQUENCE =
      ImmutableList.of(
          TaskType.SetNodeStatus, // ToBeAdded to Adding
          TaskType.AnsibleCreateServer,
          TaskType.AnsibleUpdateNodeInfo,
          TaskType.RunHooks,
          TaskType.AnsibleSetupServer,
          TaskType.RunHooks,
          TaskType.AnsibleConfigureServers,
          TaskType.AnsibleConfigureServers, // GFlags
          TaskType.AnsibleConfigureServers, // GFlags
          TaskType.SetNodeStatus,
          TaskType.AnsibleClusterServerCtl,
          TaskType.WaitForServer,
          TaskType.ModifyBlackList,
          TaskType.AnsibleClusterServerCtl,
          TaskType.WaitForServer,
          TaskType.WaitForServer, // check if postgres is up
          TaskType.SetNodeState,
          TaskType.ModifyBlackList,
          TaskType.UpdatePlacementInfo,
          TaskType.SwamperTargetsFileUpdate,
          TaskType.WaitForLeadersOnPreferredOnly,
          TaskType.ChangeMasterConfig, // Add
          TaskType.ChangeMasterConfig, // Remove
          TaskType.AnsibleClusterServerCtl, // Stop master
          TaskType.WaitForMasterLeader,
          TaskType.UpdateNodeProcess,
          TaskType.AnsibleConfigureServers, // Tservers
          TaskType.SetFlagInMemory,
          TaskType.AnsibleConfigureServers, // Masters
          TaskType.SetFlagInMemory,
          TaskType.WaitForTServerHeartBeats,
          TaskType.UniverseUpdateSucceeded);

  private static final List<TaskType> UNIVERSE_EXPAND_TASK_SEQUENCE_ON_PREM =
      ImmutableList.of(
          TaskType.PreflightNodeCheck,
          TaskType.SetNodeStatus, // ToBeAdded to Adding
          TaskType.AnsibleCreateServer,
          TaskType.AnsibleUpdateNodeInfo,
          TaskType.RunHooks,
          TaskType.AnsibleSetupServer,
          TaskType.RunHooks,
          TaskType.AnsibleConfigureServers,
          TaskType.AnsibleConfigureServers, // GFlags
          TaskType.AnsibleConfigureServers, // GFlags
          TaskType.SetNodeStatus,
          TaskType.AnsibleClusterServerCtl,
          TaskType.WaitForServer,
          TaskType.ModifyBlackList,
          TaskType.AnsibleClusterServerCtl,
          TaskType.WaitForServer,
          TaskType.WaitForServer, // check if postgres is up
          TaskType.SetNodeState,
          TaskType.ModifyBlackList,
          TaskType.UpdatePlacementInfo,
          TaskType.SwamperTargetsFileUpdate,
          TaskType.WaitForLeadersOnPreferredOnly,
          TaskType.ChangeMasterConfig, // Add
          TaskType.ChangeMasterConfig, // Remove
          TaskType.AnsibleClusterServerCtl, // Stop master
          TaskType.WaitForMasterLeader,
          TaskType.UpdateNodeProcess,
          TaskType.AnsibleConfigureServers, // Tservers
          TaskType.SetFlagInMemory,
          TaskType.AnsibleConfigureServers, // Masters
          TaskType.SetFlagInMemory,
          TaskType.WaitForTServerHeartBeats,
          TaskType.UniverseUpdateSucceeded);

  private void assertTaskSequence(
      List<TaskType> sequence, Map<Integer, List<TaskInfo>> subTasksByPosition) {
    int position = 0;
    assertEquals(sequence.size(), subTasksByPosition.size());
    for (TaskType taskType : sequence) {
      List<TaskInfo> tasks = subTasksByPosition.get(position);
      assertTrue(tasks.size() > 0);
      assertEquals(taskType, tasks.get(0).getTaskType());
      position++;
    }
  }

  @Override
  @Before
  public void setUp() {
    super.setUp();

    CatalogEntityInfo.SysClusterConfigEntryPB.Builder configBuilder =
        CatalogEntityInfo.SysClusterConfigEntryPB.newBuilder().setVersion(1);
    GetMasterClusterConfigResponse mockConfigResponse =
        new GetMasterClusterConfigResponse(1111, "", configBuilder.build(), null);
    ChangeMasterClusterConfigResponse mockMasterChangeConfigResponse =
        new ChangeMasterClusterConfigResponse(1111, "", null);
    ChangeConfigResponse mockChangeConfigResponse = mock(ChangeConfigResponse.class);
    ListTabletServersResponse mockListTabletServersResponse = mock(ListTabletServersResponse.class);
    when(mockListTabletServersResponse.getTabletServersCount()).thenReturn(10);

    try {
      when(mockClient.waitForMaster(any(), anyLong())).thenReturn(true);
      when(mockClient.getMasterClusterConfig()).thenReturn(mockConfigResponse);
      when(mockClient.changeMasterClusterConfig(any())).thenReturn(mockMasterChangeConfigResponse);
      when(mockClient.changeMasterConfig(
              anyString(), anyInt(), anyBoolean(), anyBoolean(), anyString()))
          .thenReturn(mockChangeConfigResponse);
      when(mockClient.setFlag(any(), anyString(), anyString(), anyBoolean()))
          .thenReturn(Boolean.TRUE);
      when(mockClient.listTabletServers()).thenReturn(mockListTabletServersResponse);
      ListMastersResponse listMastersResponse = mock(ListMastersResponse.class);
      when(listMastersResponse.getMasters()).thenReturn(Collections.emptyList());
      when(mockClient.listMasters()).thenReturn(listMastersResponse);
      when(mockClient.waitForAreLeadersOnPreferredOnlyCondition(anyLong())).thenReturn(true);
    } catch (Exception e) {
      fail();
    }
    mockWaits(mockClient);
    when(mockClient.waitForServer(any(), anyLong())).thenReturn(true);
    when(mockYBClient.getClient(any(), any())).thenReturn(mockClient);
    when(mockYBClient.getClientWithConfig(any())).thenReturn(mockClient);
  }

  private TaskInfo submitTask(UniverseDefinitionTaskParams taskParams) {
    try {
      UUID taskUUID = commissioner.submit(TaskType.EditUniverse, taskParams);
      return waitForTask(taskUUID);
    } catch (InterruptedException e) {
      assertNull(e.getMessage());
    }
    return null;
  }

  @Test
  public void testEditTags() throws JsonProcessingException {
    Universe universe = defaultUniverse;
    universe =
        Universe.saveDetails(
            universe.getUniverseUUID(),
            univ -> {
              univ.getUniverseDetails().getPrimaryCluster().userIntent.instanceTags =
                  ImmutableMap.of("q", "v", "q1", "v1", "q3", "v3");
            });
    UniverseDefinitionTaskParams taskParams = universe.getUniverseDetails();
    taskParams.setUniverseUUID(universe.getUniverseUUID());
    Map<String, String> newTags = ImmutableMap.of("q", "vq", "q2", "v2");
    taskParams.getPrimaryCluster().userIntent.instanceTags = newTags;

    TaskInfo taskInfo = submitTask(taskParams);
    assertEquals(Success, taskInfo.getTaskState());
    List<TaskInfo> subTasks = taskInfo.getSubTasks();
    Map<Integer, List<TaskInfo>> subTasksByPosition =
        subTasks.stream().collect(Collectors.groupingBy(TaskInfo::getPosition));

    List<TaskInfo> instanceActions = subTasksByPosition.get(0);
    assertEquals(
        new ArrayList<>(
            Arrays.asList(
                TaskType.InstanceActions, TaskType.InstanceActions, TaskType.InstanceActions)),
        instanceActions.stream()
            .map(t -> t.getTaskType())
            .collect(Collectors.toCollection(ArrayList::new)));
    JsonNode details = instanceActions.get(0).getDetails();
    assertEquals(Json.toJson(newTags), details.get("tags"));
    assertEquals("q1,q3", details.get("deleteTags").asText());

    List<TaskInfo> updateUniverseTagsTask = subTasksByPosition.get(1);
    assertEquals(
        new ArrayList<>(Collections.singletonList(TaskType.UpdateUniverseTags)),
        updateUniverseTagsTask.stream()
            .map(t -> t.getTaskType())
            .collect(Collectors.toCollection(ArrayList::new)));
    universe = Universe.getOrBadRequest(universe.getUniverseUUID());
    assertEquals(
        new HashMap<>(newTags),
        new HashMap<>(universe.getUniverseDetails().getPrimaryCluster().userIntent.instanceTags));
  }

  @Test
  public void testEditTagsUnsupportedProvider() {
    Universe universe = defaultUniverse;
    universe =
        Universe.saveDetails(
            universe.getUniverseUUID(),
            univ -> {
              univ.getUniverseDetails().getPrimaryCluster().userIntent.providerType =
                  Common.CloudType.onprem;
              univ.getUniverseDetails().getPrimaryCluster().userIntent.instanceTags =
                  ImmutableMap.of("q", "v");
            });
    UniverseDefinitionTaskParams taskParams = universe.getUniverseDetails();
    taskParams.setUniverseUUID(universe.getUniverseUUID());
    Map<String, String> newTags = ImmutableMap.of("q1", "v1");
    taskParams.getPrimaryCluster().userIntent.instanceTags = newTags;

    TaskInfo taskInfo = submitTask(taskParams);
    assertEquals(Success, taskInfo.getTaskState());
    List<TaskInfo> subTasks = taskInfo.getSubTasks();
    assertEquals(
        0, subTasks.stream().filter(t -> t.getTaskType() == TaskType.InstanceActions).count());
  }

  @Test
  public void testExpandSuccess() {
    Universe universe = defaultUniverse;
    UniverseDefinitionTaskParams taskParams = performExpand(universe);
    TaskInfo taskInfo = submitTask(taskParams);
    assertEquals(Success, taskInfo.getTaskState());
    List<TaskInfo> subTasks = taskInfo.getSubTasks();
    Map<Integer, List<TaskInfo>> subTasksByPosition =
        subTasks.stream().collect(Collectors.groupingBy(TaskInfo::getPosition));
    assertTaskSequence(UNIVERSE_EXPAND_TASK_SEQUENCE, subTasksByPosition);
    universe = Universe.getOrBadRequest(universe.getUniverseUUID());
    assertEquals(5, universe.getUniverseDetails().nodeDetailsSet.size());
  }

  @Test
  public void testExpandOnPremSuccess() {
    AvailabilityZone zone = AvailabilityZone.getByCode(onPremProvider, AZ_CODE);
    createOnpremInstance(zone);
    createOnpremInstance(zone);
    Universe universe = onPremUniverse;
    UniverseDefinitionTaskParams taskParams = performExpand(universe);
    TaskInfo taskInfo = submitTask(taskParams);
    assertEquals(Success, taskInfo.getTaskState());
    List<TaskInfo> subTasks = taskInfo.getSubTasks();
    Map<Integer, List<TaskInfo>> subTasksByPosition =
        subTasks.stream().collect(Collectors.groupingBy(TaskInfo::getPosition));
    assertTaskSequence(UNIVERSE_EXPAND_TASK_SEQUENCE_ON_PREM, subTasksByPosition);
    universe = Universe.getOrBadRequest(universe.getUniverseUUID());
    assertEquals(5, universe.getUniverseDetails().nodeDetailsSet.size());
  }

  @Test
  public void testExpandOnPremFailNoNodes() {
    Universe universe = onPremUniverse;
    AvailabilityZone zone = AvailabilityZone.getByCode(onPremProvider, AZ_CODE);
    List<NodeInstance> added = new ArrayList<>();
    added.add(createOnpremInstance(zone));
    added.add(createOnpremInstance(zone));
    UniverseDefinitionTaskParams taskParams = performExpand(universe);
    added.forEach(
        nodeInstance -> {
          nodeInstance.setInUse(true);
          nodeInstance.save();
        });
    TaskInfo taskInfo = submitTask(taskParams);
    assertEquals(Failure, taskInfo.getTaskState());
  }

  @Test
  public void testExpandOnPremFailProvision() {
    AvailabilityZone zone = AvailabilityZone.getByCode(onPremProvider, AZ_CODE);
    createOnpremInstance(zone);
    createOnpremInstance(zone);
    Universe universe = onPremUniverse;
    UniverseDefinitionTaskParams taskParams = performExpand(universe);
    preflightResponse.message = "{\"test\": false}";

    TaskInfo taskInfo = submitTask(taskParams);
    assertEquals(Failure, taskInfo.getTaskState());
  }

  private UniverseDefinitionTaskParams performExpand(Universe universe) {
    UniverseDefinitionTaskParams taskParams = new UniverseDefinitionTaskParams();
    taskParams.setUniverseUUID(universe.getUniverseUUID());
    taskParams.expectedUniverseVersion = 2;
    taskParams.nodePrefix = universe.getUniverseDetails().nodePrefix;
    taskParams.nodeDetailsSet = universe.getUniverseDetails().nodeDetailsSet;
    taskParams.clusters = universe.getUniverseDetails().clusters;
    taskParams.creatingUser = defaultUser;
    Cluster primaryCluster = taskParams.getPrimaryCluster();
    UniverseDefinitionTaskParams.UserIntent newUserIntent = primaryCluster.userIntent.clone();
    PlacementInfo pi = universe.getUniverseDetails().getPrimaryCluster().placementInfo;
    pi.cloudList.get(0).regionList.get(0).azList.get(0).numNodesInAZ = 5;
    newUserIntent.numNodes = 5;
    taskParams.getPrimaryCluster().userIntent = newUserIntent;
    PlacementInfoUtil.updateUniverseDefinition(
        taskParams, defaultCustomer.getId(), primaryCluster.uuid, EDIT);

    int iter = 1;
    for (NodeDetails node : taskParams.nodeDetailsSet) {
      node.cloudInfo.private_ip = "10.9.22." + iter;
      node.tserverRpcPort = 3333;
      iter++;
    }
    return taskParams;
  }
}
