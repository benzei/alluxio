/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.master.block;

import alluxio.RpcUtils;
import alluxio.grpc.BlockHeartbeatPRequest;
import alluxio.grpc.BlockHeartbeatPResponse;
import alluxio.grpc.BlockMasterWorkerServiceGrpc;
import alluxio.grpc.CommitBlockInUfsPRequest;
import alluxio.grpc.CommitBlockInUfsPResponse;
import alluxio.grpc.CommitBlockPRequest;
import alluxio.grpc.CommitBlockPResponse;
import alluxio.grpc.GetWorkerIdPRequest;
import alluxio.grpc.GetWorkerIdPResponse;
import alluxio.grpc.RegisterWorkerPOptions;
import alluxio.grpc.RegisterWorkerPRequest;
import alluxio.grpc.RegisterWorkerPResponse;
import alluxio.grpc.StorageList;
import alluxio.metrics.Metric;
import alluxio.grpc.GrpcUtils;
import alluxio.proto.meta.Block;

import com.google.common.base.Preconditions;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * This class is a gRPC handler for block master RPCs invoked by an Alluxio worker.
 */
public final class BlockMasterWorkerServiceHandler
    extends BlockMasterWorkerServiceGrpc.BlockMasterWorkerServiceImplBase {
  private static final Logger LOG = LoggerFactory.getLogger(BlockMasterWorkerServiceHandler.class);

  private final BlockMaster mBlockMaster;

  /**
   * Creates a new instance of {@link BlockMasterWorkerServiceHandler}.
   *
   * @param blockMaster the {@link BlockMaster} the handler uses internally
   */
  public BlockMasterWorkerServiceHandler(BlockMaster blockMaster) {
    Preconditions.checkNotNull(blockMaster, "blockMaster");
    mBlockMaster = blockMaster;
  }

  @Override
  public void blockHeartbeat(BlockHeartbeatPRequest request,
      StreamObserver<BlockHeartbeatPResponse> responseObserver) {

    final long workerId = request.getWorkerId();
    final Map<String, Long> capacityBytesOnTiers =
        request.getOptions().getCapacityBytesOnTiersMap();
    final Map<String, Long> usedBytesOnTiers = request.getUsedBytesOnTiersMap();
    final List<Long> removedBlockIds = request.getRemovedBlockIdsList();
    final Map<String, StorageList> lostStorageMap = request.getLostStorageMap();

    final Map<Block.BlockLocation, List<Long>> addedBlocksMap =
        request.getAddedBlocksList().stream()
            .collect(Collectors.toMap(e -> Block.BlockLocation.newBuilder()
                    .setTier(e.getKey().getTierAlias())
                    .setMediumType(e.getKey().getMediumType()).build(),
                e -> e.getValue().getBlockIdList(),
                (e1, e2) -> {
                  e1.addAll(e2);
                  return e1;
                }));

    final List<Metric> metrics = request.getOptions().getMetricsList()
        .stream().map(Metric::fromProto).collect(Collectors.toList());

    RpcUtils.call(LOG, (RpcUtils.RpcCallableThrowsIOException<BlockHeartbeatPResponse>) () ->
        BlockHeartbeatPResponse.newBuilder().setCommand(mBlockMaster.workerHeartbeat(workerId,
          capacityBytesOnTiers, usedBytesOnTiers, removedBlockIds, addedBlocksMap,
            lostStorageMap, metrics)).build(),
        "blockHeartbeat", "request=%s", responseObserver, request);
  }

  @Override
  public void commitBlock(CommitBlockPRequest request,
      StreamObserver<CommitBlockPResponse> responseObserver) {

    final long workerId = request.getWorkerId();
    final long usedBytesOnTier = request.getUsedBytesOnTier();
    final String tierAlias = request.getTierAlias();
    final long blockId = request.getBlockId();
    final String mediumType = request.getMediumType();
    final long length = request.getLength();

    RpcUtils.call(LOG, (RpcUtils.RpcCallableThrowsIOException<CommitBlockPResponse>) () -> {
      mBlockMaster.commitBlock(workerId, usedBytesOnTier, tierAlias,
          mediumType, blockId, length);
      return CommitBlockPResponse.getDefaultInstance();
    }, "commitBlock", "request=%s", responseObserver, request);
  }

  @Override
  public void commitBlockInUfs(CommitBlockInUfsPRequest request,
      StreamObserver<CommitBlockInUfsPResponse> responseObserver) {

    RpcUtils.call(LOG,
        (RpcUtils.RpcCallableThrowsIOException<CommitBlockInUfsPResponse>) () -> {
          mBlockMaster.commitBlockInUFS(request.getBlockId(), request.getLength());
          return CommitBlockInUfsPResponse.getDefaultInstance();
        }, "commitBlock", "request=%s", responseObserver, request);
  }

  @Override
  public void getWorkerId(GetWorkerIdPRequest request,
      StreamObserver<GetWorkerIdPResponse> responseObserver) {
    RpcUtils.call(LOG, (RpcUtils.RpcCallableThrowsIOException<GetWorkerIdPResponse>) () -> {
      return GetWorkerIdPResponse.newBuilder()
          .setWorkerId(mBlockMaster.getWorkerId(GrpcUtils.fromProto(request.getWorkerNetAddress())))
          .build();
    }, "getWorkerId", "request=%s", responseObserver, request);
  }

  @Override
  public void registerWorker(RegisterWorkerPRequest request,
      StreamObserver<RegisterWorkerPResponse> responseObserver) {
    final long workerId = request.getWorkerId();
    final List<String> storageTiers = request.getStorageTiersList();
    final Map<String, Long> totalBytesOnTiers = request.getTotalBytesOnTiersMap();
    final Map<String, Long> usedBytesOnTiers = request.getUsedBytesOnTiersMap();
    final Map<String, StorageList> lostStorageMap = request.getLostStorageMap();

    final Map<Block.BlockLocation, List<Long>> currBlocksOnLocationMap =
        request.getCurrentBlocksList().stream()
            .collect(Collectors.toMap(e -> Block.BlockLocation.newBuilder()
                    .setTier(e.getKey().getTierAlias())
                    .setMediumType(e.getKey().getMediumType()).build(),
                e -> e.getValue().getBlockIdList(),
                (e1, e2) -> {
                e1.addAll(e2);
                return e1;
                }));

    RegisterWorkerPOptions options = request.getOptions();
    RpcUtils.call(LOG,
        (RpcUtils.RpcCallableThrowsIOException<RegisterWorkerPResponse>) () -> {
          mBlockMaster.workerRegister(workerId, storageTiers, totalBytesOnTiers, usedBytesOnTiers,
              currBlocksOnLocationMap, lostStorageMap, options);
          return RegisterWorkerPResponse.getDefaultInstance();
        }, "registerWorker", "request=%s", responseObserver, request);
  }
}
