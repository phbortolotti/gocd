/*
 * Copyright 2016 ThoughtWorks, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.thoughtworks.go.server.scheduling;

import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.thoughtworks.go.config.CaseInsensitiveString;
import com.thoughtworks.go.config.PipelineConfig;
import com.thoughtworks.go.config.materials.MaterialConfigs;
import com.thoughtworks.go.config.materials.Materials;
import com.thoughtworks.go.config.remote.ConfigOrigin;
import com.thoughtworks.go.config.remote.RepoConfigOrigin;
import com.thoughtworks.go.domain.MaterialRevisions;
import com.thoughtworks.go.domain.buildcause.BuildCause;
import com.thoughtworks.go.domain.materials.Material;
import com.thoughtworks.go.domain.materials.MaterialConfig;
import com.thoughtworks.go.server.domain.Username;
import com.thoughtworks.go.server.materials.MaterialChecker;
import com.thoughtworks.go.server.materials.MaterialUpdateCompletedMessage;
import com.thoughtworks.go.server.materials.MaterialUpdateFailedMessage;
import com.thoughtworks.go.server.materials.MaterialUpdateService;
import com.thoughtworks.go.server.materials.MaterialUpdateStatusListener;
import com.thoughtworks.go.server.materials.MaterialUpdateStatusNotifier;
import com.thoughtworks.go.server.materials.SpecificMaterialRevisionFactory;
import com.thoughtworks.go.server.perf.SchedulingPerformanceLogger;
import com.thoughtworks.go.server.persistence.MaterialRepository;
import com.thoughtworks.go.server.service.*;
import com.thoughtworks.go.server.service.result.OperationResult;
import com.thoughtworks.go.server.service.result.ServerHealthStateOperationResult;
import com.thoughtworks.go.serverhealth.HealthStateScope;
import com.thoughtworks.go.serverhealth.HealthStateType;
import com.thoughtworks.go.serverhealth.ServerHealthService;
import com.thoughtworks.go.serverhealth.ServerHealthState;
import com.thoughtworks.go.util.SystemEnvironment;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import static java.lang.String.format;

@Service
public class BuildCauseProducerService {
    private static final Logger LOGGER = Logger.getLogger(BuildCauseProducerService.class);

    private SchedulingCheckerService schedulingChecker;
    private ServerHealthService serverHealthService;
    private PipelineScheduleQueue pipelineScheduleQueue;
    private GoConfigService goConfigService;
    private MaterialChecker materialChecker;
    private MaterialUpdateStatusNotifier materialUpdateStatusNotifier;
    private final MaterialUpdateService materialUpdateService;
    private final SpecificMaterialRevisionFactory specificMaterialRevisionFactory;
    private final PipelineService pipelineService;

    private TriggerMonitor triggerMonitor;
    private final SystemEnvironment systemEnvironment;
    private final MaterialConfigConverter materialConfigConverter;
    private final MaterialExpansionService materialExpansionService;
    private SchedulingPerformanceLogger schedulingPerformanceLogger;

    @Autowired
    public BuildCauseProducerService(
            SchedulingCheckerService schedulingChecker,
            ServerHealthService serverHealthService,
            PipelineScheduleQueue pipelineScheduleQueue,
            GoConfigService goConfigService,
            MaterialRepository materialRepository,
            MaterialUpdateStatusNotifier materialUpdateStatusNotifier,
            MaterialUpdateService materialUpdateService,
            SpecificMaterialRevisionFactory specificMaterialRevisionFactory,
            TriggerMonitor triggerMonitor,
            PipelineService pipelineService,
            SystemEnvironment systemEnvironment,
            MaterialConfigConverter materialConfigConverter,
            MaterialExpansionService materialExpansionService,
            SchedulingPerformanceLogger schedulingPerformanceLogger) {
        this.schedulingChecker = schedulingChecker;
        this.serverHealthService = serverHealthService;
        this.pipelineScheduleQueue = pipelineScheduleQueue;
        this.goConfigService = goConfigService;
        this.materialUpdateStatusNotifier = materialUpdateStatusNotifier;
        this.materialUpdateService = materialUpdateService;
        this.specificMaterialRevisionFactory = specificMaterialRevisionFactory;
        this.pipelineService = pipelineService;
        this.systemEnvironment = systemEnvironment;
        this.materialConfigConverter = materialConfigConverter;
        this.materialExpansionService = materialExpansionService;
        this.schedulingPerformanceLogger = schedulingPerformanceLogger;
        this.materialChecker = new MaterialChecker(materialRepository);
        this.triggerMonitor = triggerMonitor;
    }

    public void autoSchedulePipeline(String pipelineName, ServerHealthStateOperationResult result, long trackingId) {
        schedulingPerformanceLogger.autoSchedulePipelineStart(trackingId, pipelineName);

        try {
            PipelineConfig pipelineConfig = goConfigService.pipelineConfigNamed(new CaseInsensitiveString(pipelineName));
            newProduceBuildCause(pipelineConfig, new AutoBuild(goConfigService, pipelineService, pipelineName, systemEnvironment, materialChecker, serverHealthService), result, trackingId);
        } finally {
            schedulingPerformanceLogger.autoSchedulePipelineFinish(trackingId, pipelineName);
        }
    }

    public void manualSchedulePipeline(Username username, CaseInsensitiveString pipelineName, ScheduleOptions scheduleOptions, OperationResult result) {
        long trackingId = schedulingPerformanceLogger.manualSchedulePipelineStart(pipelineName.toString());

        try {
            WaitForPipelineMaterialUpdate update = new WaitForPipelineMaterialUpdate(pipelineName, new ManualBuild(username), scheduleOptions);
            update.start(result);
        } finally {
            schedulingPerformanceLogger.manualSchedulePipelineFinish(trackingId, pipelineName.toString());
        }
    }

    public void timerSchedulePipeline(PipelineConfig pipelineConfig, ServerHealthStateOperationResult result) {
        long trackingId = schedulingPerformanceLogger.timerSchedulePipelineStart(CaseInsensitiveString.str(pipelineConfig.name()));

        try {
            newProduceBuildCause(pipelineConfig, new TimedBuild(), result, trackingId);
        } finally {
            schedulingPerformanceLogger.timerSchedulePipelineFinish(trackingId, CaseInsensitiveString.str(pipelineConfig.name()));
        }
    }

    boolean markPipelineAsAlreadyTriggered(PipelineConfig pipelineConfig) {
        return triggerMonitor.markPipelineAsAlreadyTriggered(pipelineConfig);
    }

    void markPipelineAsCanBeTriggered(PipelineConfig pipelineConfig) {
        triggerMonitor.markPipelineAsCanBeTriggered(pipelineConfig);
    }

    ServerHealthState newProduceBuildCause(PipelineConfig pipelineConfig, BuildType buildType, ServerHealthStateOperationResult result, long trackingId) {
        final HashMap<String, String> stringStringHashMap = new HashMap<>();
        final HashMap<String, String> stringStringHashMap1 = new HashMap<>();
        return newProduceBuildCause(pipelineConfig, buildType, new ScheduleOptions(stringStringHashMap, stringStringHashMap1, new HashMap<String, String>()), result, trackingId);
    }

    ServerHealthState newProduceBuildCause(PipelineConfig pipelineConfig, BuildType buildType, ScheduleOptions scheduleOptions, ServerHealthStateOperationResult result, long trackingId) {
        buildType.canProduce(pipelineConfig, schedulingChecker, serverHealthService, result);
        if (!result.canContinue()) {
            return result.getServerHealthState();
        }
        String pipelineName = CaseInsensitiveString.str(pipelineConfig.name());
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("start producing build cause:" + pipelineName);
        }

        try {
            MaterialRevisions peggedRevisions = specificMaterialRevisionFactory.create(pipelineName, scheduleOptions.getSpecifiedRevisions());
            BuildCause previousBuild = pipelineScheduleQueue.mostRecentScheduled(pipelineName);

            Materials materials = materialConfigConverter.toMaterials(pipelineConfig.materialConfigs());
            MaterialConfigs expandedMaterialConfigs = materialExpansionService.expandMaterialConfigsForScheduling(pipelineConfig.materialConfigs());
            Materials expandedMaterials = materialConfigConverter.toMaterials(expandedMaterialConfigs);
            BuildCause buildCause = null;
            boolean materialConfigurationChanged = hasConfigChanged(previousBuild, expandedMaterials);
            if (previousBuild.hasNeverRun() || materialConfigurationChanged) {
                LOGGER.debug("Using latest modifications from repository for " + pipelineConfig.name());
                MaterialRevisions revisions = materialChecker.findLatestRevisions(peggedRevisions, materials);
                if (!revisions.isMissingModifications()) {
                    buildCause = buildType.onModifications(revisions, materialConfigurationChanged, null);
                    if (buildCause != null) {
                        if (!buildCause.materialsMatch(expandedMaterialConfigs)) {
                            LOGGER.warn("Error while scheduling pipeline: " + pipelineName + ". Possible Reasons: (1) Upstream pipelines have not been built yet."
                                    + " (2) Materials do not match between configuration and build-cause.");
                            return ServerHealthState.success(HealthStateType.general(HealthStateScope.forPipeline(pipelineName)));
                        }
                    }
                }
            } else {
                LOGGER.debug("Checking if materials are different for " + pipelineConfig.name());
                MaterialRevisions latestRevisions = materialChecker.findLatestRevisions(peggedRevisions, materials);
                if (!latestRevisions.isMissingModifications()) {
                    MaterialRevisions original = previousBuild.getMaterialRevisions();
                    MaterialRevisions revisions = materialChecker.findRevisionsSince(peggedRevisions, expandedMaterials, original, latestRevisions);
                    if (!revisions.hasChangedSince(original) || (buildType.shouldCheckWhetherOlderRunsHaveRunWithLatestMaterials() && materialChecker.hasPipelineEverRunWith(pipelineName, latestRevisions))) {
                        LOGGER.debug("Repository for [" + pipelineName + "] not modified");
                        buildCause = buildType.onEmptyModifications(pipelineConfig, latestRevisions);
                    } else {
                        LOGGER.debug("Repository for [" + pipelineName + "] modified; scheduling...");
                        buildCause = buildType.onModifications(revisions, materialConfigurationChanged, original);
                    }
                }
            }
            if (buildCause != null) {
                buildCause.addOverriddenVariables(scheduleOptions.getVariables());
                updateChangedRevisions(pipelineConfig.name(), buildCause);
            }
            if(isGoodReasonToSchedule(pipelineConfig, buildCause, buildType, materialConfigurationChanged))
            {
                pipelineScheduleQueue.schedule(pipelineName, buildCause);

                schedulingPerformanceLogger.sendingPipelineToTheToBeScheduledQueue(trackingId, pipelineName);
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug(format("scheduling pipeline %s with build-cause %s; config origin %s",
                            pipelineName, buildCause,pipelineConfig.getOrigin()));
                }
            }
            else
            {
                buildType.notifyPipelineNotScheduled(pipelineConfig);
            }

            serverHealthService.removeByScope(HealthStateScope.forPipeline(pipelineName));
            LOGGER.debug("finished producing buildcause for " + pipelineName);
            return ServerHealthState.success(HealthStateType.general(HealthStateScope.forPipeline(pipelineName)));
        } catch (NoCompatibleUpstreamRevisionsException ncure) {
            String message = "Error while scheduling pipeline: " + pipelineName + " as no compatible revisions were identified.";
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(message, ncure);
            }
            return showError(pipelineName, message, ncure.getMessage());
        } catch (NoModificationsPresentForDependentMaterialException e) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.error(e.getMessage(), e);
            }
            return ServerHealthState.success(HealthStateType.general(HealthStateScope.forPipeline(pipelineName)));
        } catch (Exception e) {
            String message = "Error while scheduling pipeline: " + pipelineName;
            LOGGER.error(message, e);
            return showError(pipelineName, message, e.getMessage());
        }
    }

    private boolean isGoodReasonToSchedule(PipelineConfig pipelineConfig, BuildCause buildCause, BuildType buildType,
                                           boolean materialConfigurationChanged) {
        if(buildCause == null)
            return false;

        boolean validCause = buildType.isValidBuildCause(pipelineConfig, buildCause);

        if(pipelineConfig.isConfigOriginSameAsOneOfMaterials())
        {
            if(buildCause.isForced())
            {
                // build is manual - skip scm-config consistency
                return validCause;
            }
            // then we need config and material revisions to be consistent
            if(!buildCause.pipelineConfigAndMaterialRevisionMatch(pipelineConfig))
            {
                return false;
            }

            return validCause;
        }
        else
        {
            return materialConfigurationChanged || validCause;
        }
    }

    private void updateChangedRevisions(CaseInsensitiveString pipelineName, BuildCause buildCause) {
        materialChecker.updateChangedRevisions(pipelineName, buildCause);
    }

    private ServerHealthState showError(String pipelineName, String message, String desc) {
        if (desc == null) {
            desc = "Details not available, please check server logs.";
        }
        ServerHealthState serverHealthState = ServerHealthState.error(message, desc, HealthStateType.general(HealthStateScope.forPipeline(pipelineName)));
        serverHealthService.update(serverHealthState);
        return serverHealthState;
    }

    private boolean hasConfigChanged(BuildCause previous, Materials materials) {
        return !materials.equals(previous.getMaterialRevisions().getMaterials());
    }

    private class WaitForPipelineMaterialUpdate implements MaterialUpdateStatusListener {
        private PipelineConfig pipelineConfig;
        private final BuildType buildType;
        private final ConcurrentMap<String, Material> pendingMaterials;
        private Material configMaterial;
        private boolean failed;
        private ScheduleOptions scheduleOptions;

        private WaitForPipelineMaterialUpdate(CaseInsensitiveString pipelineName, BuildType buildType, ScheduleOptions scheduleOptions) {
            this.pipelineConfig = goConfigService.pipelineConfigNamed(pipelineName);
            this.buildType = buildType;
            this.scheduleOptions = scheduleOptions;
            pendingMaterials = new ConcurrentHashMap<>();

            if(isConfigurationInMaterials())
            {
                // Then we must update config first and then continue as usual.
                // it is also possible that config will disappear at update
                RepoConfigOrigin configRepo = (RepoConfigOrigin) this.pipelineConfig.getOrigin();
                MaterialConfig materialConfig = configRepo.getMaterial();
                configMaterial = materialConfigConverter.toMaterial(materialConfig);
                pendingMaterials.putIfAbsent(materialConfig.getFingerprint(), configMaterial);
            }
            for (MaterialConfig materialConfig : pipelineConfig.materialConfigs()) {
                pendingMaterials.putIfAbsent(materialConfig.getFingerprint(), materialConfigConverter.toMaterial(materialConfig));
            }
        }

        private boolean isConfigurationInMaterials() {
            return this.pipelineConfig.isConfigOriginSameAsOneOfMaterials();
        }

        public void start(OperationResult result) {
            try {
                buildType.canProduce(pipelineConfig, schedulingChecker, serverHealthService, result);
                if (!result.canContinue()) {
                    return;
                }
                if (!markPipelineAsAlreadyTriggered(pipelineConfig)) {
                    result.conflict("Failed to force pipeline: " + pipelineConfig.name(),
                            "Pipeline already forced",
                            HealthStateType.general(HealthStateScope.forPipeline(CaseInsensitiveString.str(pipelineConfig.name()))));
                    return;
                }
                materialUpdateStatusNotifier.registerListenerFor(pipelineConfig, this);
                for (Material material : pendingMaterials.values()) {
                    materialUpdateService.updateMaterial(material);
                }
                result.accepted(format("Request to schedule pipeline %s accepted", pipelineConfig.name()), "", HealthStateType.general(HealthStateScope.forPipeline(
                        CaseInsensitiveString.str(pipelineConfig.name()))));
            } catch (RuntimeException e) {
                markPipelineAsCanBeTriggered(pipelineConfig);
                materialUpdateStatusNotifier.removeListenerFor(pipelineConfig);
                throw e;
            }
        }

        public void onMaterialUpdate(MaterialUpdateCompletedMessage message) {
            Material material = message.getMaterial();

            if (message instanceof MaterialUpdateFailedMessage) {
                String failureReason = ((MaterialUpdateFailedMessage) message).getReason();
                LOGGER.error(format("not scheduling pipeline %s after manual-trigger because update of material failed with reason %s", pipelineConfig.name(), failureReason));
                showError(CaseInsensitiveString.str(pipelineConfig.name()), format("Could not trigger pipeline '%s'", pipelineConfig.name()),
                        format("Material update failed for material '%s' because: %s", material.getDisplayName(), failureReason));
                failed = true;
            }
            else if(this.configMaterial != null &&
                    material.isSameFlyweight(this.configMaterial))
            {
                // Then we have just updated configuration material.
                // A chance to refresh our config instance.
                // This does not guarantee that this config is from newest revision:
                //  - it might have been invalid
                // then this instance is still like last time.
                // We have protection (higher) against that so this will eventually not schedule
                if(!goConfigService.hasPipelineNamed(this.pipelineConfig.name()))
                {
                    // pipeline we just triggered got removed from configuration
                    LOGGER.error(format("not scheduling pipeline %s after manual-trigger because pipeline's %s configuration was removed from origin repository",
                            pipelineConfig.name(), pipelineConfig.name()));
                    showError(CaseInsensitiveString.str(pipelineConfig.name()), format("Could not trigger pipeline '%s'", pipelineConfig.name()),
                            format("Pipeline '%s' configuration has been removed from %s", pipelineConfig.name(), configMaterial.getDisplayName()));
                    failed = true;
                }
                else {
                    //TODO #1133 we could also check if last parsing in the origin repository failed
                    PipelineConfig newPipelineConfig = goConfigService.pipelineConfigNamed(this.pipelineConfig.name());
                    ConfigOrigin oldOrigin = this.pipelineConfig.getOrigin();
                    ConfigOrigin newOrigin = newPipelineConfig.getOrigin();
                    if (!oldOrigin.equals(newOrigin)) {
                        LOGGER.debug(format("Configuration of manually-triggered pipeline %s has been updated.",
                                pipelineConfig.name()));
                        // if all seems good:
                        // In case materials have changed, we should poll new ones as well
                        for (MaterialConfig materialConfig : newPipelineConfig.materialConfigs()) {
                            if (!this.pipelineConfig.materialConfigs().hasMaterialWithFingerprint(materialConfig)) {
                                // this is a material added in recent commit, it wasn't in previous config
                                // wait for it
                                Material newMaterial = materialConfigConverter.toMaterial(materialConfig);
                                pendingMaterials.putIfAbsent(materialConfig.getFingerprint(), newMaterial);
                                // and force update of it
                                materialUpdateService.updateMaterial(newMaterial);
                                LOGGER.info(format("new material %s in %s was added after manual-trigger. Scheduled update for it.",
                                        newMaterial.getDisplayName(), pipelineConfig.name()));
                            }
                        }
                        this.pipelineConfig = newPipelineConfig;
                    }
                }
            }

            pendingMaterials.remove(material.getFingerprint());

            if (pendingMaterials.isEmpty()) {
                materialUpdateStatusNotifier.removeListenerFor(pipelineConfig);
                markPipelineAsCanBeTriggered(pipelineConfig);
                if (!failed) {
                    newProduceBuildCause(pipelineConfig, buildType, scheduleOptions, new ServerHealthStateOperationResult(), message.trackingId());
                }
            }
        }

        public boolean isListeningFor(Material material) {
            return pendingMaterials.containsKey(material.getFingerprint());
        }
    }
}
