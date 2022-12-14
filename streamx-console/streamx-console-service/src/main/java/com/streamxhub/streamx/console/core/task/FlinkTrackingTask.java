/*
 * Copyright (c) 2019 The StreamX Project
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.streamxhub.streamx.console.core.task;

import static com.streamxhub.streamx.common.enums.ExecutionMode.isKubernetesMode;

import com.streamxhub.streamx.common.enums.ExecutionMode;
import com.streamxhub.streamx.common.util.ThreadUtils;
import com.streamxhub.streamx.console.core.entity.Application;
import com.streamxhub.streamx.console.core.entity.FlinkCluster;
import com.streamxhub.streamx.console.core.entity.FlinkEnv;
import com.streamxhub.streamx.console.core.entity.SavePoint;
import com.streamxhub.streamx.console.core.enums.CheckPointStatus;
import com.streamxhub.streamx.console.core.enums.FlinkAppState;
import com.streamxhub.streamx.console.core.enums.LaunchState;
import com.streamxhub.streamx.console.core.enums.OptionState;
import com.streamxhub.streamx.console.core.enums.StopFrom;
import com.streamxhub.streamx.console.core.metrics.flink.CheckPoints;
import com.streamxhub.streamx.console.core.metrics.flink.JobsOverview;
import com.streamxhub.streamx.console.core.metrics.flink.Overview;
import com.streamxhub.streamx.console.core.metrics.yarn.AppInfo;
import com.streamxhub.streamx.console.core.service.AlertService;
import com.streamxhub.streamx.console.core.service.ApplicationService;
import com.streamxhub.streamx.console.core.service.FlinkClusterService;
import com.streamxhub.streamx.console.core.service.FlinkEnvService;
import com.streamxhub.streamx.console.core.service.SavePointService;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * <pre><b>
 *  ???????????????
 *  ???????????????
 *  ???????????????
 *  ???????????????
 * </b></pre>
 * <p>
 * This implementation is currently only used for tracing flink job on yarn
 *
 * @author benjobs
 */
@Slf4j
@Component
public class FlinkTrackingTask {

    /**
     * <pre>
     * ????????????????????????savePoint
     * ?????????RUNNING???????????????????????????,??????????????????????????????,?????????savePoint,??????????????????????????????"savepoint"
     * </pre>
     */
    private static final Cache<Long, Byte> SAVEPOINT_CACHE = Caffeine.newBuilder().expireAfterWrite(1, TimeUnit.MINUTES).build();

    /**
     * ????????????????????????????????????,??????????????????????????????????????????????????????????????????overview
     */
    private static final Cache<Long, Byte> STARTING_CACHE = Caffeine.newBuilder().expireAfterWrite(3, TimeUnit.MINUTES).build();

    /**
     * ??????????????????
     */
    private static final Map<Long, Application> TRACKING_MAP = new ConcurrentHashMap<>(0);

    /**
     * <pre>
     * StopFrom: ????????????????????????StreamX web??????????????????????????????????????????
     * ??????StreamX web??????????????????????????????????????????????????????savepoint,?????????savepoint,?????????savepoint????????????????????????savepoint,???????????????,??????????????????savepoint
     * ???:?????????????????????,??????????????????savepoint,??????????????????savepoint???????????????,???????????????????????????????????????
     * </pre>
     */
    private static final Map<Long, StopFrom> STOP_FROM_MAP = new ConcurrentHashMap<>(0);

    /**
     * ???????????????canceling??????????????????cache???,???????????????10???(2??????????????????????????????).
     */
    private static final Cache<Long, Byte> CANCELING_CACHE = Caffeine.newBuilder().expireAfterWrite(10, TimeUnit.SECONDS).build();

    @Autowired
    private SavePointService savePointService;

    @Autowired
    private AlertService alertService;

    @Autowired
    private FlinkEnvService flinkEnvService;

    @Autowired
    private FlinkClusterService flinkClusterService;

    /**
     * ??????????????????
     */
    private static final Map<Long, FlinkEnv> FLINK_ENV_MAP = new ConcurrentHashMap<>(0);

    private static final Map<Long, FlinkCluster> FLINK_CLUSTER_MAP = new ConcurrentHashMap<>(0);

    private static ApplicationService applicationService;

    private static final Map<String, Long> CHECK_POINT_MAP = new ConcurrentHashMap<>();

    private static final Map<String, Counter> CHECK_POINT_FAILED_MAP = new ConcurrentHashMap<>();

    private static final Map<Long, OptionState> OPTIONING = new ConcurrentHashMap<>();

    private Long lastTrackTime = 0L;

    private Long lastOptionTime = 0L;

    private static final Byte DEFAULT_FLAG_BYTE = Byte.valueOf("0");

    private static final ExecutorService EXECUTOR = new ThreadPoolExecutor(
            Runtime.getRuntime().availableProcessors() * 2,
            200,
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1024),
            ThreadUtils.threadFactory("flink-tracking-executor"));

    @Autowired
    public void setApplicationService(ApplicationService appService) {
        applicationService = appService;
    }

    @PostConstruct
    public void initialization() {
        getAllApplications().forEach((app) -> TRACKING_MAP.put(app.getId(), app));
    }

    @PreDestroy
    public void ending() {
        log.info("flinkTrackingTask StreamXConsole will be shutdown,persistent application to database.");
        TRACKING_MAP.forEach((k, v) -> persistent(v));
    }

    /**
     * <p> <strong> NOTE: ??????????????????????????????</strong>
     * <p> <strong>1) ??????????????????????????????????????????????????????(??????|??????),??????????????????????????????????????????,??????1?????????,??????10??????(10???)</strong></p>
     * <p> <strong>2) ???????????????????????????,5???????????????</strong></p>
     */
    @Scheduled(fixedDelay = 1000)
    public void execute() {
        // ??????5????????????????????????
        long trackInterval = 1000L * 5;
        //10?????????
        long optionInterval = 1000L * 10;

        //1) ??????????????????????????????,????????????????????????...(??????,??????)??????????????????????????????.
        if (lastTrackTime == null || !OPTIONING.isEmpty()) {
            tracking();
        } else if (System.currentTimeMillis() - lastOptionTime <= optionInterval) {
            //2) ???????????????????????????????????????10????????????(??????????????????)
            tracking();
        } else if (System.currentTimeMillis() - lastTrackTime >= trackInterval) {
            //3) ??????????????????,?????????????????????????????????????????????5???(????????????????????????,???5?????????)
            tracking();
        }
    }

    private void tracking() {
        lastTrackTime = System.currentTimeMillis();
        TRACKING_MAP.entrySet().stream()
                .filter(trkElement -> !isKubernetesMode(trkElement.getValue().getExecutionMode()))
                .forEach(trkElement -> EXECUTOR.execute(() -> {
                    long key = trkElement.getKey();
                    Application application = trkElement.getValue();
                    final StopFrom stopFrom = STOP_FROM_MAP.getOrDefault(key, null) == null ? StopFrom.NONE : STOP_FROM_MAP.get(key);
                    final OptionState optionState = OPTIONING.get(key);
                    try {
                        // 1) ???flink???REST Api???????????????
                        assert application.getId() != null;
                        getFromFlinkRestApi(application, stopFrom);
                    } catch (Exception flinkException) {
                        // 2) ??? YARN REST api???????????????
                        try {
                            getFromYarnRestApi(application, stopFrom);
                        } catch (Exception yarnException) {
                            /**
                             * 3) ???flink???restAPI???yarn???restAPI???????????????</br>
                             * ?????????????????????????????????????????????????????????????????????????????????,?????????:</br>
                             * 1: ????????????????????????????????????????????????(??????????????????STARTING)</br>
                             */
                            if (optionState == null || !optionState.equals(OptionState.STARTING)) {
                                //?????????????????????appId
                                if (application.getState() != FlinkAppState.MAPPING.getValue()) {
                                    log.error("flinkTrackingTask getFromFlinkRestApi and getFromYarnRestApi error,job failed,savePoint obsoleted!");
                                    if (StopFrom.NONE.equals(stopFrom)) {
                                        savePointService.obsolete(application.getId());
                                        application.setState(FlinkAppState.LOST.getValue());
                                        alertService.alert(application, FlinkAppState.LOST);
                                    } else {
                                        application.setState(FlinkAppState.CANCELED.getValue());
                                    }
                                }
                                /**
                                 * ????????????????????????????????????????????????????????????,?????????????????????,????????????????????????????????????</br>
                                 * ?????????savepoint.
                                 */
                                cleanSavepoint(application);
                                cleanOptioning(optionState, key);
                                application.setEndTime(new Date());
                                this.persistentAndClean(application);

                                FlinkAppState appState = FlinkAppState.of(application.getState());
                                if (appState.equals(FlinkAppState.FAILED) || appState.equals(FlinkAppState.LOST)) {
                                    alertService.alert(application, FlinkAppState.of(application.getState()));
                                    if (appState.equals(FlinkAppState.FAILED)) {
                                        try {
                                            applicationService.start(application, true);
                                        } catch (Exception e) {
                                            e.printStackTrace();
                                        }
                                    }
                                }
                            }
                        }
                    }
                }));
    }

    /**
     * ???flink restapi?????????????????????????????????????????????...
     *
     * @param application
     * @param stopFrom
     * @throws Exception
     */
    private void getFromFlinkRestApi(Application application, StopFrom stopFrom) throws Exception {
        FlinkEnv flinkEnv = getFlinkEnvCache(application);
        FlinkCluster flinkCluster = getFlinkClusterCache(application);
        JobsOverview jobsOverview = application.httpJobsOverview(flinkEnv, flinkCluster);
        Optional<JobsOverview.Job> optional;
        if (ExecutionMode.isYarnMode(application.getExecutionMode())) {
            optional = jobsOverview.getJobs().size() > 1 ? jobsOverview.getJobs().stream().filter(a -> StringUtils.equals(application.getJobId(), a.getId())).findFirst() : jobsOverview.getJobs().stream().findFirst();
        } else {
            optional = jobsOverview.getJobs().stream().filter(x -> x.getId().equals(application.getJobId())).findFirst();
        }
        if (optional.isPresent()) {

            JobsOverview.Job jobOverview = optional.get();
            FlinkAppState currentState = FlinkAppState.of(jobOverview.getState());

            if (!FlinkAppState.OTHER.equals(currentState)) {
                // 1) set info from JobOverview
                handleJobOverview(application, jobOverview);

                //2) CheckPoints
                handleCheckPoints(application);

                //3) savePoint obsolete check and NEED_START check
                OptionState optionState = OPTIONING.get(application.getId());
                // cpu????????????,???Running????????????????????????
                if (currentState.equals(FlinkAppState.RUNNING)) {
                    handleRunningState(application, optionState, currentState);
                } else {
                    handleNotRunState(application, optionState, currentState, stopFrom);
                }
            }
        }
    }

    /**
     * ???????????????????????????
     *
     * @param application
     * @param jobOverview
     * @throws IOException
     */
    private void handleJobOverview(Application application, JobsOverview.Job jobOverview) throws IOException {
        // 1) jobId???restapi?????????????????????
        application.setJobId(jobOverview.getId());
        application.setTotalTask(jobOverview.getTasks().getTotal());
        application.setOverview(jobOverview.getTasks());

        // 2) duration
        long startTime = jobOverview.getStartTime();
        long endTime = jobOverview.getEndTime();
        if (application.getStartTime() == null) {
            application.setStartTime(new Date(startTime));
        } else if (startTime != application.getStartTime().getTime()) {
            application.setStartTime(new Date(startTime));
        }
        if (endTime != -1) {
            if (application.getEndTime() == null || endTime != application.getEndTime().getTime()) {
                application.setEndTime(new Date(endTime));
            }
        }
        application.setDuration(jobOverview.getDuration());

        // 3) overview,????????????????????????Overview??????.
        if (STARTING_CACHE.getIfPresent(application.getId()) != null) {
            FlinkEnv flinkEnv = getFlinkEnvCache(application);
            FlinkCluster flinkCluster = getFlinkClusterCache(application);
            Overview override = application.httpOverview(flinkEnv, flinkCluster);
            if (override != null && override.getSlotsTotal() > 0) {
                STARTING_CACHE.invalidate(application.getId());
                application.setTotalTM(override.getTaskmanagers());
                application.setTotalSlot(override.getSlotsTotal());
                application.setAvailableSlot(override.getSlotsAvailable());
            }
        }
    }

    /**
     * ???????????????checkPoint
     *
     * @param application
     * @throws IOException
     */
    private void handleCheckPoints(Application application) throws Exception {
        FlinkEnv flinkEnv = getFlinkEnvCache(application);
        FlinkCluster flinkCluster = getFlinkClusterCache(application);
        CheckPoints checkPoints = application.httpCheckpoints(flinkEnv, flinkCluster);
        if (checkPoints != null) {
            CheckPoints.Latest latest = checkPoints.getLatest();
            if (latest != null) {
                CheckPoints.CheckPoint checkPoint = latest.getCompleted();
                if (checkPoint != null) {
                    CheckPointStatus status = checkPoint.getCheckPointStatus();
                    if (CheckPointStatus.COMPLETED.equals(status)) {
                        Long latestId = CHECK_POINT_MAP.get(application.getJobId());
                        if (latestId == null || latestId < checkPoint.getId()) {
                            SavePoint savePoint = new SavePoint();
                            savePoint.setAppId(application.getId());
                            savePoint.setLatest(true);
                            savePoint.setType(checkPoint.getCheckPointType().get());
                            savePoint.setPath(checkPoint.getExternalPath());
                            savePoint.setTriggerTime(new Date(checkPoint.getTriggerTimestamp()));
                            savePoint.setCreateTime(new Date());
                            savePointService.save(savePoint);
                            CHECK_POINT_MAP.put(application.getJobId(), checkPoint.getId());
                        }
                    } else if (CheckPointStatus.FAILED.equals(status) && application.cpFailedTrigger()) {
                        Counter counter = CHECK_POINT_FAILED_MAP.get(application.getJobId());
                        if (counter == null) {
                            CHECK_POINT_FAILED_MAP.put(application.getJobId(), new Counter(checkPoint.getTriggerTimestamp()));
                        } else {
                            //x??????????????????Y???CheckPoint??????????????????
                            long minute = counter.getDuration(checkPoint.getTriggerTimestamp());
                            if (minute <= application.getCpFailureRateInterval()
                                    && counter.getCount() >= application.getCpMaxFailureInterval()) {
                                CHECK_POINT_FAILED_MAP.remove(application.getJobId());
                                if (application.getCpFailureAction() == 1) {
                                    alertService.alert(application, CheckPointStatus.FAILED);
                                } else {
                                    applicationService.restart(application);
                                }
                            } else {
                                counter.add();
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * ????????????????????????,?????????????????????.
     *
     * @param application
     * @param optionState
     * @param currentState
     */
    private void handleRunningState(Application application, OptionState optionState, FlinkAppState currentState) {
        /**
         * ???????????????????????? "STARTING" ?????????????????????????????????"RUNNING",????????????????????????????????????
         * ???:job???????????????????????????????????????:
         * NEED_RESTART_AFTER_CONF_UPDATE(???????????????????????????????????????)
         * NEED_RESTART_AFTER_SQL_UPDATE(flink sql?????????????????????)
         * NEED_RESTART_AFTER_ROLLBACK(???????????????????????????)
         * NEED_RESTART_AFTER_DEPLOY(?????????????????????????????????)
         */
        if (OptionState.STARTING.equals(optionState)) {
            LaunchState launchState = LaunchState.of(application.getLaunch());
            //??????????????????????????????????????? ??? ???????????????????????????
            switch (launchState) {
                case NEED_RESTART:
                case NEED_ROLLBACK:
                    //?????????????????????????????????.
                    application.setLaunch(LaunchState.DONE.get());
                    break;
                default:
                    break;
            }
        }
        // ???????????????running,???savePointCache??????????????????,????????????????????????savepoint
        if (SAVEPOINT_CACHE.getIfPresent(application.getId()) != null) {
            application.setOptionState(OptionState.SAVEPOINTING.getValue());
        } else {
            application.setOptionState(OptionState.NONE.getValue());
        }
        application.setState(currentState.getValue());
        TRACKING_MAP.put(application.getId(), application);
        cleanOptioning(optionState, application.getId());
    }

    /**
     * ?????????????????????,????????????
     *
     * @param application
     * @param optionState
     * @param currentState
     * @param stopFrom
     */
    private void handleNotRunState(Application application,
                                   OptionState optionState,
                                   FlinkAppState currentState,
                                   StopFrom stopFrom) throws Exception {
        switch (currentState) {
            case CANCELLING:
                CANCELING_CACHE.put(application.getId(), DEFAULT_FLAG_BYTE);
                cleanSavepoint(application);
                application.setState(currentState.getValue());
                TRACKING_MAP.put(application.getId(), application);
                break;
            case CANCELED:
                log.info("flinkTrackingTask getFromFlinkRestApi, job state {}, stop tracking and delete stopFrom!", currentState.name());
                cleanSavepoint(application);
                application.setState(currentState.getValue());
                if (StopFrom.NONE.equals(stopFrom)) {
                    log.info("flinkTrackingTask getFromFlinkRestApi, job cancel is not form streamX,savePoint obsoleted!");
                    savePointService.obsolete(application.getId());
                    alertService.alert(application, FlinkAppState.CANCELED);
                }
                //??????stopFrom
                STOP_FROM_MAP.remove(application.getId());
                //?????????application????????????????????????
                persistentAndClean(application);
                cleanOptioning(optionState, application.getId());
                break;
            case FAILED:
                cleanSavepoint(application);
                //??????stopFrom
                STOP_FROM_MAP.remove(application.getId());
                application.setState(FlinkAppState.FAILED.getValue());
                //?????????application????????????????????????
                persistentAndClean(application);
                alertService.alert(application, FlinkAppState.FAILED);
                applicationService.start(application, true);
                break;
            case RESTARTING:
                log.info("flinkTrackingTask getFromFlinkRestApi, job state {},add to starting", currentState.name());
                STARTING_CACHE.put(application.getId(), DEFAULT_FLAG_BYTE);
                break;
            default:
                application.setState(currentState.getValue());
                TRACKING_MAP.put(application.getId(), application);
        }
    }

    /**
     * <p><strong>??? yarn?????????job???????????????,??????flink??????????????????,????????????????????????"CANCELED"</strong>
     *
     * @param application
     * @param stopFrom
     */
    private void getFromYarnRestApi(Application application, StopFrom stopFrom) throws Exception {
        log.debug("flinkTrackingTask getFromYarnRestApi starting...");
        OptionState optionState = OPTIONING.get(application.getId());

        /**
         * ?????????????????????canceling(??????????????????flink restServer???????????????canceling)
         * ??????????????????????????????(flink restServer?????????),?????????????????????CANCELED
         */
        Byte flag = CANCELING_CACHE.getIfPresent(application.getId());
        if (flag != null) {
            log.info("flinkTrackingTask previous state: canceling.");
            if (StopFrom.NONE.equals(stopFrom)) {
                log.error("flinkTrackingTask query previous state was canceling and stopFrom NotFound,savePoint obsoleted!");
                savePointService.obsolete(application.getId());
            }
            application.setState(FlinkAppState.CANCELED.getValue());
            cleanSavepoint(application);
            cleanOptioning(optionState, application.getId());
            this.persistentAndClean(application);
        } else {
            // 2)???yarn???restApi???????????????
            AppInfo appInfo = application.httpYarnAppInfo();
            if (appInfo == null) {
                if (!ExecutionMode.REMOTE.equals(application.getExecutionModeEnum())) {
                    throw new RuntimeException("flinkTrackingTask getFromYarnRestApi failed ");
                }
            } else {
                try {
                    String state = appInfo.getApp().getState();
                    FlinkAppState flinkAppState = FlinkAppState.of(state);
                    if (FlinkAppState.OTHER.equals(flinkAppState)) {
                        return;
                    }
                    if (FlinkAppState.KILLED.equals(flinkAppState)) {
                        if (StopFrom.NONE.equals(stopFrom)) {
                            log.error("flinkTrackingTask getFromYarnRestApi,job was killed and stopFrom NotFound,savePoint obsoleted!");
                            savePointService.obsolete(application.getId());
                        }
                        flinkAppState = FlinkAppState.CANCELED;
                        cleanSavepoint(application);
                        application.setEndTime(new Date());
                    }
                    if (FlinkAppState.SUCCEEDED.equals(flinkAppState)) {
                        flinkAppState = FlinkAppState.FINISHED;
                    }
                    application.setState(flinkAppState.getValue());
                    //?????????????????????,?????????YARN REST api????????????????????????
                    cleanOptioning(optionState, application.getId());
                    this.persistentAndClean(application);

                    if (flinkAppState.equals(FlinkAppState.FAILED) || flinkAppState.equals(FlinkAppState.LOST)) {
                        alertService.alert(application, flinkAppState);
                        if (flinkAppState.equals(FlinkAppState.FAILED)) {
                            applicationService.start(application, true);
                        }
                    }
                } catch (Exception e) {
                    if (!ExecutionMode.REMOTE.equals(application.getExecutionModeEnum())) {
                        throw new RuntimeException("flinkTrackingTask getFromYarnRestApi error,", e);
                    }
                }
            }
        }

    }

    private void cleanOptioning(OptionState optionState, Long key) {
        if (optionState != null) {
            lastOptionTime = System.currentTimeMillis();
            OPTIONING.remove(key);
        }
    }

    private void cleanSavepoint(Application application) {
        SAVEPOINT_CACHE.invalidate(application.getId());
        application.setOptionState(OptionState.NONE.getValue());
    }

    private static List<Application> getAllApplications() {
        QueryWrapper<Application> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("tracking", 1)
                .notIn("execution_mode", ExecutionMode.getKubernetesMode());
        return applicationService.list(queryWrapper);
    }

    private static void persistent(Application application) {
        applicationService.updateTracking(application);
    }

    private void persistentAndClean(Application application) {
        persistent(application);
        stopTracking(application.getId());
    }


    /**
     * <p><strong>1????????????????????????????????????</strong></p></br>
     * <p><strong>NOTE:???????????????????????????????????????,??????????????????????????????????????????????????????,???????????????????????????application????????????????????????????????????
     * ????????????????????????????????????????????????????????????????????????,???????????????????????????????????????????????????,????????????????????????.
     * </strong></p>
     */
    @Scheduled(fixedDelay = 1000 * 60)
    public void persistent() {
        TRACKING_MAP.forEach((k, v) -> persistent(v));
    }

    // ===============================  static public method...  =========================================

    /**
     * ?????????????????????...
     */
    public static void setOptionState(Long appId, OptionState state) {
        if (isKubernetesApp(appId)) {
            return;
        }
        log.info("flinkTrackingTask setOptioning");
        Long optioningTime = System.currentTimeMillis();
        OPTIONING.put(appId, state);
        //???streamx??????
        if (state.equals(OptionState.CANCELLING)) {
            STOP_FROM_MAP.put(appId, StopFrom.STREAMX);
        }
    }

    public static void addTracking(Application application) {
        if (isKubernetesApp(application)) {
            return;
        }
        log.info("flinkTrackingTask add app to tracking,appId:{}", application.getId());
        TRACKING_MAP.put(application.getId(), application);
        STARTING_CACHE.put(application.getId(), DEFAULT_FLAG_BYTE);
    }

    public static void addSavepoint(Long appId) {
        if (isKubernetesApp(appId)) {
            return;
        }
        log.info("flinkTrackingTask add app to savepoint,appId:{}", appId);
        SAVEPOINT_CACHE.put(appId, DEFAULT_FLAG_BYTE);
    }

    /**
     * ?????????????????????application????????????,????????????????????????,??????cache?????????????????????????????????????????????.
     *
     * @param appId
     * @param callable
     */
    public static Object refreshTracking(Long appId, Callable callable) throws Exception {
        if (isKubernetesApp(appId)) {
            // notes: k8s flink tracking monitor don't need to flush or refresh cache proactively.
            return callable.call();
        }
        log.debug("flinkTrackingTask flushing app,appId:{}", appId);
        Application application = TRACKING_MAP.get(appId);
        if (application != null) {
            persistent(application);
            Object result = callable.call();
            TRACKING_MAP.put(appId, applicationService.getById(appId));
            return result;
        }
        return callable.call();
    }

    public static void refreshTracking(Runnable runnable) {
        log.info("flinkTrackingTask flushing all application starting");
        getAllTrackingApp().values().forEach(app -> {
            Application application = TRACKING_MAP.get(app.getId());
            if (application != null) {
                persistent(application);
            }
        });

        runnable.run();

        getAllApplications().forEach((app) -> {
            if (TRACKING_MAP.get(app.getId()) != null) {
                TRACKING_MAP.put(app.getId(), app);
            }
        });
        log.info("flinkTrackingTask flushing all application end!");
    }

    public static void stopTracking(Long appId) {
        if (isKubernetesApp(appId)) {
            return;
        }
        log.info("flinkTrackingTask stop app,appId:{}", appId);
        TRACKING_MAP.remove(appId);
    }

    public static Map<Long, Application> getAllTrackingApp() {
        return TRACKING_MAP;
    }

    public static Application getTracking(Long appId) {
        return TRACKING_MAP.get(appId);
    }

    @Data
    public static class Counter {
        private Long timestamp;
        private Integer count;

        public Counter(Long timestamp) {
            this.timestamp = timestamp;
            this.count = 1;
        }

        public void add() {
            this.count += 1;
        }

        public long getDuration(Long currentTimestamp) {
            return (currentTimestamp - this.getTimestamp()) / 1000 / 60;
        }
    }

    private static boolean isKubernetesApp(Application application) {
        return K8sFlinkTrkMonitorWrapper.isKubernetesApp(application);
    }

    private static boolean isKubernetesApp(Long appId) {
        Application app = TRACKING_MAP.get(appId);
        return K8sFlinkTrkMonitorWrapper.isKubernetesApp(app);
    }

    private FlinkEnv getFlinkEnvCache(Application application) {
        FlinkEnv flinkEnv = FLINK_ENV_MAP.get(application.getVersionId());
        if (flinkEnv == null) {
            flinkEnv = flinkEnvService.getByAppId(application.getId());
            FLINK_ENV_MAP.put(flinkEnv.getId(), flinkEnv);
        }
        return flinkEnv;
    }

    private FlinkCluster getFlinkClusterCache(Application application) {
        if (ExecutionMode.isRemoteMode(application.getExecutionModeEnum())) {
            FlinkCluster flinkCluster = FLINK_CLUSTER_MAP.get(application.getFlinkClusterId());
            if (flinkCluster == null) {
                flinkCluster = flinkClusterService.getById(application.getFlinkClusterId());
                FLINK_CLUSTER_MAP.put(application.getFlinkClusterId(), flinkCluster);
            }
            return flinkCluster;
        }
        return null;
    }

    public static Map<Long, FlinkEnv> getFlinkEnvMap() {
        return FLINK_ENV_MAP;
    }

}
