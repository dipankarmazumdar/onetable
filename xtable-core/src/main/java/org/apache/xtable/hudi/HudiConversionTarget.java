/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
 
package org.apache.xtable.hudi;

import static org.apache.hudi.index.HoodieIndex.IndexType.INMEMORY;

import java.io.IOException;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;

import org.apache.avro.Schema;
import org.apache.hadoop.conf.Configuration;

import org.apache.hudi.avro.model.HoodieActionInstant;
import org.apache.hudi.avro.model.HoodieCleanFileInfo;
import org.apache.hudi.avro.model.HoodieCleanMetadata;
import org.apache.hudi.avro.model.HoodieCleanerPlan;
import org.apache.hudi.client.HoodieJavaWriteClient;
import org.apache.hudi.client.HoodieTimelineArchiver;
import org.apache.hudi.client.WriteStatus;
import org.apache.hudi.client.common.HoodieJavaEngineContext;
import org.apache.hudi.common.HoodieCleanStat;
import org.apache.hudi.common.config.HoodieMetadataConfig;
import org.apache.hudi.common.engine.HoodieEngineContext;
import org.apache.hudi.common.model.HoodieBaseFile;
import org.apache.hudi.common.model.HoodieCleaningPolicy;
import org.apache.hudi.common.model.HoodieCommitMetadata;
import org.apache.hudi.common.model.HoodieFileGroup;
import org.apache.hudi.common.table.HoodieTableMetaClient;
import org.apache.hudi.common.table.TableSchemaResolver;
import org.apache.hudi.common.table.timeline.HoodieActiveTimeline;
import org.apache.hudi.common.table.timeline.HoodieInstant;
import org.apache.hudi.common.table.timeline.HoodieTimeline;
import org.apache.hudi.common.table.timeline.TimelineMetadataUtils;
import org.apache.hudi.common.table.timeline.TimelineUtils;
import org.apache.hudi.common.table.view.TableFileSystemView;
import org.apache.hudi.common.util.CleanerUtils;
import org.apache.hudi.common.util.ExternalFilePathUtil;
import org.apache.hudi.common.util.Option;
import org.apache.hudi.common.util.collection.Pair;
import org.apache.hudi.config.HoodieArchivalConfig;
import org.apache.hudi.config.HoodieCleanConfig;
import org.apache.hudi.config.HoodieIndexConfig;
import org.apache.hudi.config.HoodieWriteConfig;
import org.apache.hudi.hadoop.CachingPath;
import org.apache.hudi.metadata.HoodieTableMetadataWriter;
import org.apache.hudi.table.HoodieJavaTable;
import org.apache.hudi.table.action.clean.CleanPlanner;

import com.google.common.annotations.VisibleForTesting;

import org.apache.xtable.avro.AvroSchemaConverter;
import org.apache.xtable.conversion.TargetTable;
import org.apache.xtable.exception.NotSupportedException;
import org.apache.xtable.exception.ReadException;
import org.apache.xtable.exception.UpdateException;
import org.apache.xtable.model.InternalTable;
import org.apache.xtable.model.metadata.TableSyncMetadata;
import org.apache.xtable.model.schema.InternalField;
import org.apache.xtable.model.schema.InternalPartitionField;
import org.apache.xtable.model.schema.InternalSchema;
import org.apache.xtable.model.storage.InternalFilesDiff;
import org.apache.xtable.model.storage.PartitionFileGroup;
import org.apache.xtable.model.storage.TableFormat;
import org.apache.xtable.spi.sync.ConversionTarget;

@Log4j2
public class HudiConversionTarget implements ConversionTarget {
  private BaseFileUpdatesExtractor baseFileUpdatesExtractor;
  private AvroSchemaConverter avroSchemaConverter;
  private HudiTableManager hudiTableManager;
  private CommitStateCreator commitStateCreator;
  private int timelineRetentionInHours;
  private int maxNumDeltaCommitsBeforeCompaction;
  private String tableDataPath;
  private Optional<HoodieTableMetaClient> metaClient;
  private CommitState commitState;

  public HudiConversionTarget() {}

  @VisibleForTesting
  HudiConversionTarget(
      TargetTable targetTable,
      Configuration configuration,
      int maxNumDeltaCommitsBeforeCompaction) {
    this(
        targetTable.getBasePath(),
        (int) targetTable.getMetadataRetention().toHours(),
        maxNumDeltaCommitsBeforeCompaction,
        BaseFileUpdatesExtractor.of(
            new HoodieJavaEngineContext(configuration), new CachingPath(targetTable.getBasePath())),
        AvroSchemaConverter.getInstance(),
        HudiTableManager.of(configuration),
        CommitState::new);
  }

  @VisibleForTesting
  HudiConversionTarget(
      String tableDataPath,
      int timelineRetentionInHours,
      int maxNumDeltaCommitsBeforeCompaction,
      BaseFileUpdatesExtractor baseFileUpdatesExtractor,
      AvroSchemaConverter avroSchemaConverter,
      HudiTableManager hudiTableManager,
      CommitStateCreator commitStateCreator) {

    _init(
        tableDataPath,
        timelineRetentionInHours,
        maxNumDeltaCommitsBeforeCompaction,
        baseFileUpdatesExtractor,
        avroSchemaConverter,
        hudiTableManager,
        commitStateCreator);
  }

  private void _init(
      String tableDataPath,
      int timelineRetentionInHours,
      int maxNumDeltaCommitsBeforeCompaction,
      BaseFileUpdatesExtractor baseFileUpdatesExtractor,
      AvroSchemaConverter avroSchemaConverter,
      HudiTableManager hudiTableManager,
      CommitStateCreator commitStateCreator) {
    this.tableDataPath = tableDataPath;
    this.baseFileUpdatesExtractor = baseFileUpdatesExtractor;
    this.timelineRetentionInHours = timelineRetentionInHours;
    this.maxNumDeltaCommitsBeforeCompaction = maxNumDeltaCommitsBeforeCompaction;
    this.avroSchemaConverter = avroSchemaConverter;
    this.hudiTableManager = hudiTableManager;
    // create meta client if table already exists
    this.metaClient = hudiTableManager.loadTableMetaClientIfExists(tableDataPath);
    this.commitStateCreator = commitStateCreator;
  }

  @Override
  public void init(TargetTable targetTable, Configuration configuration) {
    _init(
        targetTable.getBasePath(),
        (int) targetTable.getMetadataRetention().toHours(),
        HoodieMetadataConfig.COMPACT_NUM_DELTA_COMMITS.defaultValue(),
        BaseFileUpdatesExtractor.of(
            new HoodieJavaEngineContext(configuration), new CachingPath(targetTable.getBasePath())),
        AvroSchemaConverter.getInstance(),
        HudiTableManager.of(configuration),
        CommitState::new);
  }

  @FunctionalInterface
  interface CommitStateCreator {
    CommitState create(
        HoodieTableMetaClient metaClient,
        String instantTime,
        int timelineRetentionInHours,
        int maxNumDeltaCommitsBeforeCompaction);
  }

  @Override
  public void syncSchema(InternalSchema schema) {
    if (metaClient.isPresent()) {
      validateRecordKeysAreNotModified(schema);
    }
    commitState.setSchema(avroSchemaConverter.fromInternalSchema(schema));
  }

  private void validateRecordKeysAreNotModified(InternalSchema schema) {
    Option<String[]> recordKeyFields = getMetaClient().getTableConfig().getRecordKeyFields();
    if (recordKeyFields.isPresent()) {
      List<String> existingHudiRecordKeys = Arrays.asList(recordKeyFields.get());
      List<String> schemaFieldsList =
          schema.getRecordKeyFields().stream()
              .map(InternalField::getPath)
              .collect(Collectors.toList());
      if (!schemaFieldsList.equals(existingHudiRecordKeys)) {
        Set<String> newKeys =
            schemaFieldsList.stream()
                .filter(k -> !existingHudiRecordKeys.contains(k))
                .collect(Collectors.toSet());
        Set<String> removedKeys =
            existingHudiRecordKeys.stream()
                .filter(k -> !schemaFieldsList.contains(k))
                .collect(Collectors.toSet());
        log.error(
            String.format(
                "Record key fields cannot be changed after creating Hudi table. "
                    + "New keys: %s, Removed keys: %s",
                newKeys, removedKeys));
        throw new NotSupportedException(
            "Record key fields cannot be changed after creating Hudi table");
      }
    }
  }

  @Override
  public void syncPartitionSpec(List<InternalPartitionField> partitionSpec) {
    List<String> existingPartitionFields =
        getMetaClient()
            .getTableConfig()
            .getPartitionFields()
            .map(Arrays::asList)
            .orElse(Collections.emptyList());
    List<String> newPartitionFields =
        partitionSpec.stream()
            .map(InternalPartitionField::getSourceField)
            .map(InternalField::getPath)
            .collect(Collectors.toList());
    if (!existingPartitionFields.equals(newPartitionFields)) {
      throw new NotSupportedException("Partition spec cannot be changed after creating Hudi table");
    }
  }

  @Override
  public void syncMetadata(TableSyncMetadata metadata) {
    commitState.setTableSyncMetadata(metadata);
  }

  @Override
  public void syncFilesForSnapshot(List<PartitionFileGroup> partitionedDataFiles) {
    BaseFileUpdatesExtractor.ReplaceMetadata replaceMetadata =
        baseFileUpdatesExtractor.extractSnapshotChanges(
            partitionedDataFiles, getMetaClient(), commitState.getInstantTime());
    commitState.setReplaceMetadata(replaceMetadata);
  }

  @Override
  public void syncFilesForDiff(InternalFilesDiff internalFilesDiff) {
    BaseFileUpdatesExtractor.ReplaceMetadata replaceMetadata =
        baseFileUpdatesExtractor.convertDiff(internalFilesDiff, commitState.getInstantTime());
    commitState.setReplaceMetadata(replaceMetadata);
  }

  @Override
  public void beginSync(InternalTable table) {
    if (!metaClient.isPresent()) {
      metaClient = Optional.of(hudiTableManager.initializeHudiTable(tableDataPath, table));
    } else {
      // make sure meta client has up-to-date view of the timeline
      getMetaClient().reloadActiveTimeline();
    }
    String instant = HudiInstantUtils.convertInstantToCommit(table.getLatestCommitTime());
    this.commitState =
        commitStateCreator.create(
            getMetaClient(), instant, timelineRetentionInHours, maxNumDeltaCommitsBeforeCompaction);
  }

  @Override
  public void completeSync() {
    commitState.commit();
    commitState = null;
  }

  @Override
  public Optional<TableSyncMetadata> getTableMetadata() {
    return metaClient.flatMap(
        client ->
            client
                .reloadActiveTimeline()
                .getCommitsTimeline()
                .filterCompletedInstants()
                .lastInstant()
                .toJavaOptional()
                .flatMap(instant -> getMetadata(instant, client)));
  }

  @Override
  public String getTableFormat() {
    return TableFormat.HUDI;
  }

  @Override
  public Optional<String> getTargetCommitIdentifier(String sourceIdentifier) {
    if (!metaClient.isPresent()) {
      return Optional.empty();
    }
    return getTargetCommitIdentifier(sourceIdentifier, metaClient.get());
  }

  Optional<String> getTargetCommitIdentifier(
      String sourceIdentifier, HoodieTableMetaClient metaClient) {

    HoodieTimeline commitTimeline = metaClient.getCommitsTimeline();

    for (HoodieInstant instant : commitTimeline.getInstants()) {
      try {
        Optional<TableSyncMetadata> optionalMetadata = getMetadata(instant, metaClient);
        if (!optionalMetadata.isPresent()) {
          continue;
        }

        TableSyncMetadata metadata = optionalMetadata.get();
        if (sourceIdentifier.equals(metadata.getSourceIdentifier())) {
          return Optional.of(instant.getTimestamp());
        }
      } catch (Exception e) {
        log.warn("Failed to parse commit metadata for instant: {}", instant, e);
      }
    }
    return Optional.empty();
  }

  private HoodieTableMetaClient getMetaClient() {
    return metaClient.orElseThrow(
        () -> new IllegalStateException("beginSync must be called before calling this method"));
  }

  private Optional<TableSyncMetadata> getMetadata(
      HoodieInstant instant, HoodieTableMetaClient metaClient) {
    try {
      HoodieCommitMetadata commitMetadata =
          TimelineUtils.getCommitMetadata(instant, metaClient.getActiveTimeline());
      String sourceMetadataJson =
          commitMetadata.getExtraMetadata().get(TableSyncMetadata.XTABLE_METADATA);
      return TableSyncMetadata.fromJson(sourceMetadataJson);
    } catch (Exception e) {
      log.warn("Failed to parse commit metadata for instant: {}", instant, e);
      return Optional.empty();
    }
  }

  static class CommitState {
    private HoodieTableMetaClient metaClient;
    @Getter private final String instantTime;
    private final int timelineRetentionInHours;
    private final int maxNumDeltaCommitsBeforeCompaction;
    private List<WriteStatus> writeStatuses;
    @Setter private Schema schema;
    @Setter private TableSyncMetadata tableSyncMetadata;
    private Map<String, List<String>> partitionToReplacedFileIds;

    private CommitState(
        HoodieTableMetaClient metaClient,
        String instantTime,
        int timelineRetentionInHours,
        int maxNumDeltaCommitsBeforeCompaction) {
      this.metaClient = metaClient;
      this.instantTime = instantTime;
      this.timelineRetentionInHours = timelineRetentionInHours;
      this.maxNumDeltaCommitsBeforeCompaction = maxNumDeltaCommitsBeforeCompaction;
      this.schema = null;
      this.writeStatuses = Collections.emptyList();
      this.tableSyncMetadata = null;
      this.partitionToReplacedFileIds = Collections.emptyMap();
    }

    public void setReplaceMetadata(BaseFileUpdatesExtractor.ReplaceMetadata replaceMetadata) {
      if (!writeStatuses.isEmpty() || !partitionToReplacedFileIds.isEmpty()) {
        throw new IllegalArgumentException("Replace metadata can only be set once");
      }
      this.writeStatuses = replaceMetadata.getWriteStatuses();
      this.partitionToReplacedFileIds = replaceMetadata.getPartitionToReplacedFileIds();
    }

    public void commit() {
      if (schema == null) {
        try {
          // reuse existing table schema if no schema is provided as part of this commit
          schema = new TableSchemaResolver(metaClient).getTableAvroSchema();
        } catch (Exception ex) {
          throw new ReadException("Unable to read Hudi table schema", ex);
        }
      }
      HoodieWriteConfig writeConfig =
          getWriteConfig(
              schema,
              getNumInstantsToRetain(),
              maxNumDeltaCommitsBeforeCompaction,
              timelineRetentionInHours);
      HoodieEngineContext engineContext = new HoodieJavaEngineContext(metaClient.getHadoopConf());
      try (HoodieJavaWriteClient<?> writeClient =
          new HoodieJavaWriteClient<>(engineContext, writeConfig)) {
        writeClient.startCommitWithTime(instantTime, HoodieTimeline.REPLACE_COMMIT_ACTION);
        metaClient
            .getActiveTimeline()
            .transitionReplaceRequestedToInflight(
                new HoodieInstant(
                    HoodieInstant.State.REQUESTED,
                    HoodieTimeline.REPLACE_COMMIT_ACTION,
                    instantTime),
                Option.empty());
        writeClient.commit(
            instantTime,
            writeStatuses,
            getExtraMetadata(),
            HoodieTimeline.REPLACE_COMMIT_ACTION,
            partitionToReplacedFileIds);
        // if the metaclient was created before the table's first commit, we need to reload it to
        // pick up the metadata table context
        if (!metaClient.getTableConfig().isMetadataTableAvailable()) {
          metaClient = HoodieTableMetaClient.reload(metaClient);
        }
        HoodieJavaTable<?> table =
            HoodieJavaTable.create(writeClient.getConfig(), engineContext, metaClient);
        // clean up old commits and archive them
        markInstantsAsCleaned(table, writeClient.getConfig(), engineContext);
        runArchiver(table, writeClient.getConfig(), engineContext);
      }
    }

    private int getNumInstantsToRetain() {
      String commitCutoff =
          HudiInstantUtils.convertInstantToCommit(
              HudiInstantUtils.parseFromInstantTime(instantTime)
                  .minus(timelineRetentionInHours, ChronoUnit.HOURS));
      // count number of completed commits after the cutoff
      return metaClient
          .getActiveTimeline()
          .filterCompletedInstants()
          .findInstantsAfter(commitCutoff)
          .countInstants();
    }

    private void markInstantsAsCleaned(
        HoodieJavaTable<?> table,
        HoodieWriteConfig writeConfig,
        HoodieEngineContext engineContext) {
      CleanPlanner<?, ?, ?, ?> planner = new CleanPlanner<>(engineContext, table, writeConfig);
      Option<HoodieInstant> earliestInstant = planner.getEarliestCommitToRetain();
      // since we're retaining based on time, we should exit early if earliestInstant is empty
      if (!earliestInstant.isPresent()) {
        return;
      }
      List<String> partitionsToClean;
      try {
        partitionsToClean = planner.getPartitionPathsToClean(earliestInstant);
      } catch (IOException ex) {
        throw new ReadException("Unable to get partitions to clean", ex);
      }
      if (partitionsToClean.isEmpty()) {
        return;
      }

      HoodieActiveTimeline activeTimeline = metaClient.getActiveTimeline();
      TableFileSystemView fsView = table.getHoodieView();
      Map<String, List<HoodieCleanFileInfo>> cleanInfoPerPartition =
          partitionsToClean.stream()
              .map(
                  partition ->
                      Pair.of(partition, planner.getDeletePaths(partition, earliestInstant)))
              .filter(deletePaths -> !deletePaths.getValue().getValue().isEmpty())
              .collect(
                  Collectors.toMap(
                      Pair::getKey,
                      deletePathsForPartition -> {
                        String partition = deletePathsForPartition.getKey();
                        // we need to manipulate the path to properly clean from the metadata table,
                        // so we map the file path to the base file
                        Map<String, HoodieBaseFile> baseFilesByPath =
                            fsView
                                .getAllReplacedFileGroups(partition)
                                .flatMap(HoodieFileGroup::getAllBaseFiles)
                                .collect(
                                    Collectors.toMap(HoodieBaseFile::getPath, Function.identity()));
                        return deletePathsForPartition.getValue().getValue().stream()
                            .map(
                                cleanFileInfo -> {
                                  HoodieBaseFile baseFile =
                                      baseFilesByPath.get(cleanFileInfo.getFilePath());
                                  return new HoodieCleanFileInfo(
                                      ExternalFilePathUtil.appendCommitTimeAndExternalFileMarker(
                                          baseFile.getFileName(), baseFile.getCommitTime()),
                                      false);
                                })
                            .collect(Collectors.toList());
                      }));
      // there is nothing to clean, so exit early
      if (cleanInfoPerPartition.isEmpty()) {
        return;
      }
      // create a clean instant write after this latest commit
      String cleanTime =
          HudiInstantUtils.convertInstantToCommit(
              HudiInstantUtils.parseFromInstantTime(instantTime).plus(1, ChronoUnit.SECONDS));
      // create a metadata table writer in order to mark files as deleted in the table
      // the deleted entries are cleaned up in the metadata table during compaction to control the
      // growth of the table
      try (HoodieTableMetadataWriter hoodieTableMetadataWriter =
          table.getMetadataWriter(cleanTime).get()) {
        HoodieCleanerPlan cleanerPlan =
            new HoodieCleanerPlan(
                earliestInstant
                    .map(
                        earliestInstantToRetain ->
                            new HoodieActionInstant(
                                earliestInstantToRetain.getTimestamp(),
                                earliestInstantToRetain.getAction(),
                                earliestInstantToRetain.getState().name()))
                    .orElse(null),
                instantTime,
                writeConfig.getCleanerPolicy().name(),
                Collections.emptyMap(),
                CleanPlanner.LATEST_CLEAN_PLAN_VERSION,
                cleanInfoPerPartition,
                Collections.emptyList());
        // create a clean instant and mark it as requested with the clean plan
        HoodieInstant requestedCleanInstant =
            new HoodieInstant(
                HoodieInstant.State.REQUESTED, HoodieTimeline.CLEAN_ACTION, cleanTime);
        activeTimeline.saveToCleanRequested(
            requestedCleanInstant, TimelineMetadataUtils.serializeCleanerPlan(cleanerPlan));
        HoodieInstant inflightClean =
            activeTimeline.transitionCleanRequestedToInflight(
                requestedCleanInstant, Option.empty());
        List<HoodieCleanStat> cleanStats =
            cleanInfoPerPartition.entrySet().stream()
                .map(
                    entry -> {
                      String partitionPath = entry.getKey();
                      List<String> deletePaths =
                          entry.getValue().stream()
                              .map(HoodieCleanFileInfo::getFilePath)
                              .collect(Collectors.toList());
                      return new HoodieCleanStat(
                          HoodieCleaningPolicy.KEEP_LATEST_COMMITS,
                          partitionPath,
                          deletePaths,
                          deletePaths,
                          Collections.emptyList(),
                          earliestInstant.get().getTimestamp(),
                          instantTime);
                    })
                .collect(Collectors.toList());
        HoodieCleanMetadata cleanMetadata =
            CleanerUtils.convertCleanMetadata(cleanTime, Option.empty(), cleanStats);
        // update the metadata table with the clean metadata so the files' metadata are marked for
        // deletion
        hoodieTableMetadataWriter.performTableServices(Option.empty());
        hoodieTableMetadataWriter.update(cleanMetadata, cleanTime);
        // mark the commit as complete on the table timeline
        activeTimeline.transitionCleanInflightToComplete(
            inflightClean, TimelineMetadataUtils.serializeCleanMetadata(cleanMetadata));
      } catch (Exception ex) {
        throw new UpdateException("Unable to clean Hudi timeline", ex);
      }
    }

    private void runArchiver(
        HoodieJavaTable<?> table, HoodieWriteConfig config, HoodieEngineContext engineContext) {
      // trigger archiver manually
      try {
        HoodieTimelineArchiver archiver = new HoodieTimelineArchiver(config, table);
        archiver.archiveIfRequired(engineContext, true);
      } catch (IOException ex) {
        throw new UpdateException("Unable to archive Hudi timeline", ex);
      }
    }

    private Option<Map<String, String>> getExtraMetadata() {
      Map<String, String> extraMetadata =
          Collections.singletonMap(TableSyncMetadata.XTABLE_METADATA, tableSyncMetadata.toJson());
      return Option.of(extraMetadata);
    }

    private HoodieWriteConfig getWriteConfig(
        Schema schema,
        int numCommitsToKeep,
        int maxNumDeltaCommitsBeforeCompaction,
        int timelineRetentionInHours) {
      Properties properties = new Properties();
      properties.setProperty(HoodieMetadataConfig.AUTO_INITIALIZE.key(), "false");
      return HoodieWriteConfig.newBuilder()
          .withIndexConfig(HoodieIndexConfig.newBuilder().withIndexType(INMEMORY).build())
          .withPath(metaClient.getBasePathV2().toString())
          .withPopulateMetaFields(metaClient.getTableConfig().populateMetaFields())
          .withEmbeddedTimelineServerEnabled(false)
          .withSchema(schema == null ? "" : schema.toString())
          .withArchivalConfig(
              HoodieArchivalConfig.newBuilder()
                  .archiveCommitsWith(
                      Math.max(0, numCommitsToKeep - 1), Math.max(1, numCommitsToKeep))
                  .withAutoArchive(false)
                  .build())
          .withCleanConfig(
              HoodieCleanConfig.newBuilder()
                  .withCleanerPolicy(HoodieCleaningPolicy.KEEP_LATEST_BY_HOURS)
                  .cleanerNumHoursRetained(timelineRetentionInHours)
                  .withAutoClean(false)
                  .build())
          .withMetadataConfig(
              HoodieMetadataConfig.newBuilder()
                  .enable(true)
                  .withProperties(properties)
                  .withMetadataIndexColumnStats(true)
                  .withMaxNumDeltaCommitsBeforeCompaction(maxNumDeltaCommitsBeforeCompaction)
                  .build())
          .build();
    }
  }
}
