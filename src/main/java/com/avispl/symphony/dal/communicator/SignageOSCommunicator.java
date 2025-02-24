/*
 * Copyright (c) 2025 AVI-SPL, Inc. All Rights Reserved.
 */
package com.avispl.symphony.dal.communicator;

import com.avispl.symphony.api.dal.control.Controller;
import com.avispl.symphony.api.dal.dto.control.AdvancedControllableProperty;
import com.avispl.symphony.api.dal.dto.control.ControllableProperty;
import com.avispl.symphony.api.dal.dto.monitor.ExtendedStatistics;
import com.avispl.symphony.api.dal.dto.monitor.Statistics;
import com.avispl.symphony.api.dal.dto.monitor.aggregator.AggregatedDevice;
import com.avispl.symphony.api.dal.error.CommandFailureException;
import com.avispl.symphony.api.dal.monitor.Monitorable;
import com.avispl.symphony.api.dal.monitor.aggregator.Aggregator;
import com.avispl.symphony.dal.aggregator.parser.AggregatedDeviceProcessor;
import com.avispl.symphony.dal.aggregator.parser.PropertiesMapping;
import com.avispl.symphony.dal.aggregator.parser.PropertiesMappingParser;
import com.avispl.symphony.dal.communicator.data.Constant;
import com.avispl.symphony.dal.communicator.data.PowerAction;
import com.avispl.symphony.dal.communicator.data.TelemetrySetting;
import com.avispl.symphony.dal.util.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.HttpClients;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.*;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestTemplate;

import javax.security.auth.login.FailedLoginException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static com.avispl.symphony.dal.communicator.data.Constant.Properties.*;
import static com.avispl.symphony.dal.util.ControllablePropertyFactory.*;

/**
 * SignageOS API communicator.
 * Supported functionality list:
 * - SignageOS devices metadata
 * - Action Logs monitoring
 * - Debug settings monitoring and control
 * - Storage status monitoring
 * - System information monitoring
 * - Time settings of the device
 * - Device uptime monitoring
 * - Device VPN and Proxy status
 * - Latest screenshots information
 * - Device controls (applet and device reload and refresh, screen orientation, framerate and resolution,
 * volume and remote control switching)
 *
 * @author Author Name
 * @since 1.0.0
 */
public class SignageOSCommunicator extends RestCommunicator implements Aggregator, Monitorable, Controller {
    /**
     * Interceptor for RestTemplate that checks for the response headers populated for certain endpoints
     * such as metrics, to control the amount of requests left per day.
     *
     * @author Maksym.Rossiytsev
     * @since 1.0.0
     */
    class SignageOSInterceptor implements ClientHttpRequestInterceptor {
        @Override
        public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
            ClientHttpResponse response = null;
            try {
                response = execution.execute(request, body);

                if (response == null) {
                    failedAPI = true;
                } else {
                    failedAPI = false;
                }
                int statusCode = response.getRawStatusCode();
                if (statusCode == 403 || statusCode == 401) {
                    failedLogin = true;
                } else {
                    failedLogin = false;
                }
                String requestPath = request.getURI().getPath();
                // Return for all uris that do not require additional processing
                if (!requestPath.contains("/device")) {
                    return response;
                }
                ClientHttpResponse finalResponse = response;
                HttpHeaders headers = response.getHeaders();
                StringBuilder link = new StringBuilder();
                boolean containsLink = false;
                if (headers != null && headers.containsKey("Link")) {
                    containsLink = true;
                    link.append(headers.get("Link").get(0));
                }

                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                StreamUtils.copy(response.getBody(), buffer);

                // Convert the byte array to a string (if you want to modify it as a JSON response)
                JsonNode jsonResponse = objectMapper.readTree(buffer.toString(StandardCharsets.UTF_8.name()));
                String modifiedResponse;
                if (jsonResponse.isArray()) {
                    ObjectNode responseBody = objectMapper.createObjectNode();
                    if (containsLink) {
                        responseBody.put("nextPageLink", link.toString());
                    }
                    responseBody.set("items", jsonResponse);
                    modifiedResponse = responseBody.toString();
                } else {
                    modifiedResponse = jsonResponse.toString();
                }

                return new ClientHttpResponse() {
                    @Override
                    public HttpStatus getStatusCode() throws IOException {
                        return finalResponse.getStatusCode();
                    }
                    @Override
                    public int getRawStatusCode() throws IOException {
                        return finalResponse.getRawStatusCode();
                    }
                    @Override
                    public String getStatusText() throws IOException {
                        return finalResponse.getStatusText();
                    }
                    @Override
                    public void close() {
                        finalResponse.close();
                    }
                    @Override
                    public InputStream getBody() {
                        return new ByteArrayInputStream(modifiedResponse.getBytes(StandardCharsets.UTF_8));
                    }
                    @Override
                    public HttpHeaders getHeaders() {
                        return finalResponse.getHeaders();
                    }
                };
            } catch (Exception e) {
                //knownErrors.put(LOGIN_ERROR_KEY, e.getMessage());
                logger.error("An exception occurred during request execution", e);
            }
            return response;
        }
    }

    /**
     * Paginated response interface, needed to delegate paginated meetings to a caller
     * @author Maksym.Rossiytsev
     * @since 1.0.0
     * */
    private interface PaginatedResponseProcessor {
        void process(JsonNode response);
    }
    /**
     * Delegated process, needed to properly process generic requests and handle
     * @author Maksym.Rossiytsev
     * @since 1.0.0
     * */
    private interface PropertyGroupRetrievalProcessor {
        void process() throws Exception;
    }
    /**
     * Process that is running constantly and triggers collecting data from SignageOS API endpoints, based on the given timeouts and thresholds.
     *
     * @author Maksym.Rossiytsev
     * @since 1.0.0
     */
    class SignageOSDataLoader implements Runnable {
        private volatile boolean inProgress;

        public SignageOSDataLoader() {
            logDebugMessage("Creating new device data loader.");
            inProgress = true;
        }

        @Override
        public void run() {
            logDebugMessage("Entering device data loader active stage.");
            mainloop:
            while (inProgress) {
                long startCycle = System.currentTimeMillis();
                try {
                    try {
                        TimeUnit.MILLISECONDS.sleep(500);
                    } catch (InterruptedException e) {
                        // Ignore for now
                    }

                    if (!inProgress) {
                        logDebugMessage("Main data collection thread is not in progress, breaking.");
                        break mainloop;
                    }

                    updateAggregatorStatus();
                    // next line will determine whether SignageOS monitoring was paused
                    if (devicePaused) {
                        logDebugMessage("The device communicator is paused, data collector is not active.");
                        continue mainloop;
                    }
                    try {
                        logDebugMessage("Fetching devices list.");
                        fetchDevices();
                    } catch (Exception e) {
                        logger.error("Error occurred during device list retrieval: " + e.getMessage() + " with cause: " + e.getCause().getMessage(), e);
                    }

                    if (!inProgress) {
                        logDebugMessage("The data collection thread is not in progress. Breaking the loop.");
                        break mainloop;
                    }

                    int aggregatedDevicesCount = aggregatedDevices.size();
                    if (aggregatedDevicesCount == 0) {
                        logDebugMessage("No devices collected in the main data collection thread so far. Continuing.");
                        continue mainloop;
                    }

                    while (nextDevicesCollectionIterationTimestamp > System.currentTimeMillis()) {
                        try {
                            TimeUnit.MILLISECONDS.sleep(1000);
                        } catch (InterruptedException e) {
                            //
                        }
                    }

                    for (AggregatedDevice aggregatedDevice : aggregatedDevices.values()) {
                        if (!inProgress) {
                            logDebugMessage("The data collection thread is not in progress. Breaking the data update loop.");
                            break;
                        }
                        if (executorService == null) {
                            logDebugMessage("Executor service reference is null. Breaking the execution.");
                            break;
                        }
                        devicesExecutionPool.add(executorService.submit(() -> {
                            String deviceId = aggregatedDevice.getDeviceId();
                            if (!propertyGroupsRetrievedTimestamps.containsKey(deviceId)) {
                                propertyGroupsRetrievedTimestamps.put(deviceId, new ConcurrentHashMap<>());
                            }
                            try {
                                fetchStorage(aggregatedDevice);
                                fetchScreenshots(aggregatedDevice);
                                fetchActionLogs(aggregatedDevice);
                                fetchVPN(aggregatedDevice);
                                fetchUptime(aggregatedDevice);
                                fetchTelemetryRecords(aggregatedDevice);
                            } catch (Exception e) {
                                logger.error(String.format("Exception during SignageOS '%s' data processing.", aggregatedDevice.getDeviceName()), e);
                            }
                        }));
                    }
                    do {
                        try {
                            TimeUnit.MILLISECONDS.sleep(500);
                        } catch (InterruptedException e) {
                            logger.error("Interrupted exception during main loop execution", e);
                            if (!inProgress) {
                                logDebugMessage("Breaking after the main loop execution");
                                break;
                            }
                        }
                        devicesExecutionPool.removeIf(Future::isDone);
                    } while (!devicesExecutionPool.isEmpty());

                    // We don't want to fetch devices statuses too often, so by default it's currentTime + 30s
                    // otherwise - the variable is reset by the retrieveMultipleStatistics() call, which
                    // launches devices detailed statistics collection
                    nextDevicesCollectionIterationTimestamp = System.currentTimeMillis() + 30000;

                    lastMonitoringCycleDuration = (System.currentTimeMillis() - startCycle) / 1000;
                    logDebugMessage("Finished collecting devices statistics cycle at " + new Date() + ", total duration: " + lastMonitoringCycleDuration);
                } catch (Exception e) {
                    logger.error("Unexpected error occurred during main device collection cycle", e);
                }
            }
            logDebugMessage("Main device collection loop is completed, in progress marker: " + inProgress);
            // Finished collecting
        }

        /**
         * Triggers main loop to stop
         */
        public void stop() {
            logDebugMessage("Main device details collection loop is stopped!");
            inProgress = false;
        }
    }

    /** Failed login state of aggregator */
    private volatile boolean failedLogin = false;
    /** Failed API state of aggregator */
    private volatile boolean failedAPI = false;
    /** Adapter metadata properties - adapter version and build date */
    private Properties adapterProperties;
    /** Device property processor, that uses yml mapping to extract properties from json */
    private AggregatedDeviceProcessor aggregatedDeviceProcessor;
    /** Collection of all aggregated devices, cached in memory */
    private Map<String, AggregatedDevice> aggregatedDevices = new HashMap<>();
    /** Status of async data collector service */
    private boolean serviceRunning = false;
    /** Object mapper for json<->string transformations */
    ObjectMapper objectMapper = new ObjectMapper();
    /** API response page size */
    private int requestPageSize = 100;
    /**
     * Interceptor for RestTemplate that injects
     * authorization header and fixes malformed headers sent by XIO backend
     */
    private ClientHttpRequestInterceptor headerInterceptor = new SignageOSInterceptor();

    /**
     * Indicates whether a device is considered as paused.
     * True by default so if the system is rebooted and the actual value is lost -> the device won't start stats
     * collection unless the {@link SignageOSCommunicator#retrieveMultipleStatistics()} method is called which will change it
     * to a correct value
     */
    private volatile boolean devicePaused = true;

    /**
     * This parameter holds timestamp of when we need to stop performing API calls
     * It used when device stop retrieving statistic. Updated each time of called #retrieveMultipleStatistics
     */
    private volatile long validRetrieveStatisticsTimestamp;

    /**
     * Aggregator inactivity timeout. If the {@link SignageOSCommunicator#retrieveMultipleStatistics()}  method is not
     * called during this period of time - device is considered to be paused, thus the Cloud API
     * is not supposed to be called
     */
    private static final long retrieveStatisticsTimeOut = 180000;

    /**
     * We don't want the statistics to be collected constantly, because if there's not a big list of devices -
     * new devices statistics loop will be launched before the next monitoring iteration. To avoid that -
     * this variable stores a timestamp which validates it, so when the devices statistics is done collecting, variable
     * is set to currentTime + 30s, at the same time, calling {@link #retrieveMultipleStatistics()} and updating the
     * {@link #aggregatedDevices} resets it to the currentTime timestamp, which will re-activate data collection.
     */
    private volatile long nextDevicesCollectionIterationTimestamp;

    /**
     * Executor that runs all the async operations, that {@link #deviceDataLoader} is posting and
     * {@link #devicesExecutionPool} is keeping track of
     */
    private ExecutorService executorService;

    /**
     * Runner service responsible for collecting data and posting processes to {@link #devicesExecutionPool}
     */
    private SignageOSDataLoader deviceDataLoader;

    /**
     * Pool for keeping all the async operations in, to track any operations in progress and cancel them if needed
     */
    private List<Future> devicesExecutionPool = new ArrayList<>();

    /**
     * How much time last monitoring cycle took to finish
     * */
    private Long lastMonitoringCycleDuration;

    /**
     * Device adapter instantiation timestamp.
     */
    private long adapterInitializationTimestamp;

    /**
     * Device filter by application type
     * */
    List<String> applicationTypeFilter = new ArrayList<>();

    /**
     * Device filter by device model
     * */
    List<String> modelFilter = new ArrayList<>();

    /**
     * Device filter by brand
     * */
    List<String> brandFilter = new ArrayList<>();

    /**
     * Device filter by location
     * */
    List<String> locationUIDFilter = new ArrayList<>();

    /**
     * Displayed property groups configuration
     * */
    List<String> displayPropertyGroups = new ArrayList<>();

    /**
     * Historical property groups configuration
     * */
    List<String> historicalProperties = new ArrayList<>();

    /**
     * Number of latest monitored screenshots
     * */
    int numberOfScreenshots = 1;

    /**
     * Number of latest monitored action logs
     * */
    int numberOfActionLogs = 1;

    /**
     * Data retrieval timestamps, to grant pacing based on
     * {@link #metadataRetrievalInterval}, {@link #telemetrySettingsRetrievalInterval}, {@link #storageRetrievalInterval},
     * {@link #storageRetrievalInterval}, {@link #screenshotsRetrievalInterval}, {@link #actionLogsRetrievalInterval}
     * {@link #vpnRetrievalInterval}, {@link #uptimeRetrievalInterval}
     * */
    ConcurrentHashMap<String, ConcurrentHashMap<String, Long>> propertyGroupsRetrievedTimestamps = new ConcurrentHashMap<>();

    /**
     * Latest metadata retrieval timestamp value, to grant pacing based on {@link #metadataRetrievalInterval}
     * */
    private Long metadataRetrievalTimestamp = 0L;
    /**
     * Device metadata retrieval interval
     * */
    private Integer metadataRetrievalInterval = 30000;
    /**
     * Device telemetry retrieval interval
     * */
    private Integer telemetrySettingsRetrievalInterval = 30000;
    /**
     * Device storage retrieval interval
     * */
    private Integer storageRetrievalInterval = 30000;
    /**
     * Device screenshots retrieval interval
     * */
    private Integer screenshotsRetrievalInterval = 30000;
    /**
     * Device action logs retrieval interval
     * */
    private Integer actionLogsRetrievalInterval = 30000;
    /**
     * Device vpn status retrieval interval
     * */
    private Integer vpnRetrievalInterval = 30000;
    /**
     * Device uptime retrieval interval
     * */
    private Integer uptimeRetrievalInterval = 30000;

    /**
     * Default communicator constructor.
     * Initializes {@link AggregatedDeviceProcessor}, adapter metadata properties and async device collector
     * */
    public SignageOSCommunicator() throws IOException {
        Map<String, PropertiesMapping> mapping = new PropertiesMappingParser().loadYML("mapping/model-mapping.yml", getClass());
        aggregatedDeviceProcessor = new AggregatedDeviceProcessor(mapping);
        adapterProperties = new Properties();
        adapterProperties.load(getClass().getResourceAsStream("/version.properties"));

        executorService = Executors.newFixedThreadPool(5);
        executorService.submit(deviceDataLoader = new SignageOSDataLoader());
    }

    /**
     * Retrieves {@link #metadataRetrievalInterval}
     *
     * @return value of {@link #metadataRetrievalInterval}
     */
    public Integer getMetadataRetrievalInterval() {
        return metadataRetrievalInterval;
    }

    /**
     * Sets {@link #metadataRetrievalInterval} value
     *
     * @param metadataRetrievalInterval new value of {@link #metadataRetrievalInterval}
     */
    public void setMetadataRetrievalInterval(Integer metadataRetrievalInterval) {
        this.metadataRetrievalInterval = metadataRetrievalInterval;
    }

    /**
     * Retrieves {@link #telemetrySettingsRetrievalInterval}
     *
     * @return value of {@link #telemetrySettingsRetrievalInterval}
     */
    public Integer getTelemetrySettingsRetrievalInterval() {
        return telemetrySettingsRetrievalInterval;
    }

    /**
     * Sets {@link #telemetrySettingsRetrievalInterval} value
     *
     * @param telemetrySettingsRetrievalInterval new value of {@link #telemetrySettingsRetrievalInterval}
     */
    public void setTelemetrySettingsRetrievalInterval(Integer telemetrySettingsRetrievalInterval) {
        this.telemetrySettingsRetrievalInterval = telemetrySettingsRetrievalInterval;
    }

    /**
     * Retrieves {@link #storageRetrievalInterval}
     *
     * @return value of {@link #storageRetrievalInterval}
     */
    public Integer getStorageRetrievalInterval() {
        return storageRetrievalInterval;
    }

    /**
     * Sets {@link #storageRetrievalInterval} value
     *
     * @param storageRetrievalInterval new value of {@link #storageRetrievalInterval}
     */
    public void setStorageRetrievalInterval(Integer storageRetrievalInterval) {
        this.storageRetrievalInterval = storageRetrievalInterval;
    }

    /**
     * Retrieves {@link #screenshotsRetrievalInterval}
     *
     * @return value of {@link #screenshotsRetrievalInterval}
     */
    public Integer getScreenshotsRetrievalInterval() {
        return screenshotsRetrievalInterval;
    }

    /**
     * Sets {@link #screenshotsRetrievalInterval} value
     *
     * @param screenshotsRetrievalInterval new value of {@link #screenshotsRetrievalInterval}
     */
    public void setScreenshotsRetrievalInterval(Integer screenshotsRetrievalInterval) {
        this.screenshotsRetrievalInterval = screenshotsRetrievalInterval;
    }

    /**
     * Retrieves {@link #actionLogsRetrievalInterval}
     *
     * @return value of {@link #actionLogsRetrievalInterval}
     */
    public Integer getActionLogsRetrievalInterval() {
        return actionLogsRetrievalInterval;
    }

    /**
     * Sets {@link #actionLogsRetrievalInterval} value
     *
     * @param actionLogsRetrievalInterval new value of {@link #actionLogsRetrievalInterval}
     */
    public void setActionLogsRetrievalInterval(Integer actionLogsRetrievalInterval) {
        this.actionLogsRetrievalInterval = actionLogsRetrievalInterval;
    }

    /**
     * Retrieves {@link #vpnRetrievalInterval}
     *
     * @return value of {@link #vpnRetrievalInterval}
     */
    public Integer getVpnRetrievalInterval() {
        return vpnRetrievalInterval;
    }

    /**
     * Sets {@link #vpnRetrievalInterval} value
     *
     * @param vpnRetrievalInterval new value of {@link #vpnRetrievalInterval}
     */
    public void setVpnRetrievalInterval(Integer vpnRetrievalInterval) {
        this.vpnRetrievalInterval = vpnRetrievalInterval;
    }

    /**
     * Retrieves {@link #uptimeRetrievalInterval}
     *
     * @return value of {@link #uptimeRetrievalInterval}
     */
    public Integer getUptimeRetrievalInterval() {
        return uptimeRetrievalInterval;
    }

    /**
     * Sets {@link #uptimeRetrievalInterval} value
     *
     * @param uptimeRetrievalInterval new value of {@link #uptimeRetrievalInterval}
     */
    public void setUptimeRetrievalInterval(Integer uptimeRetrievalInterval) {
        this.uptimeRetrievalInterval = uptimeRetrievalInterval;
    }

    /**
     * Retrieves {@link #requestPageSize}
     *
     * @return value of {@link #requestPageSize}
     */
    public int getRequestPageSize() {
        return requestPageSize;
    }

    /**
     * Sets {@link #requestPageSize} value
     *
     * @param requestPageSize new value of {@link #requestPageSize}
     */
    public void setRequestPageSize(int requestPageSize) {
        this.requestPageSize = requestPageSize;
    }

    /**
     * Retrieves {@link #numberOfActionLogs}
     *
     * @return value of {@link #numberOfActionLogs}
     */
    public int getNumberOfActionLogs() {
        return numberOfActionLogs;
    }

    /**
     * Sets {@link #numberOfActionLogs} value
     *
     * @param numberOfActionLogs new value of {@link #numberOfActionLogs}
     */
    public void setNumberOfActionLogs(int numberOfActionLogs) {
        this.numberOfActionLogs = Math.min(numberOfActionLogs, 10);
    }

    /**
     * Retrieves {@link #numberOfScreenshots}
     *
     * @return value of {@link #numberOfScreenshots}
     */
    public int getNumberOfScreenshots() {
        return numberOfScreenshots;
    }

    /**
     * Sets {@link #numberOfScreenshots} value
     *
     * @param numberOfScreenshots new value of {@link #numberOfScreenshots}
     */
    public void setNumberOfScreenshots(int numberOfScreenshots) {
        this.numberOfScreenshots = Math.min(numberOfScreenshots, 10);
    }

    /**
     * Retrieves {@link #historicalProperties}
     *
     * @return value of {@link #historicalProperties}
     */
    public String getHistoricalProperties() {
        return String.join(",", historicalProperties);
    }

    /**
     * Sets {@link #historicalProperties} value
     *
     * @param historicalProperties new value of {@link #historicalProperties}
     */
    public void setHistoricalProperties(String historicalProperties) {
        this.historicalProperties = Arrays.stream(historicalProperties.split(",")).map(String::trim).filter(StringUtils::isNotNullOrEmpty).collect(Collectors.toList());;
    }

    /**
     * Retrieves {@link #applicationTypeFilter}
     *
     * @return value of {@link #applicationTypeFilter}
     */
    public String getApplicationTypeFilter() {
        return String.join(",", applicationTypeFilter);
    }

    /**
     * Sets {@link #applicationTypeFilter} value
     *
     * @param applicationTypeFilter new value of {@link #applicationTypeFilter}
     */
    public void setApplicationTypeFilter(String applicationTypeFilter) {
        this.applicationTypeFilter = Arrays.stream(applicationTypeFilter.split(",")).map(String::trim).filter(StringUtils::isNotNullOrEmpty).collect(Collectors.toList());
    }

    /**
     * Retrieves {@link #modelFilter}
     *
     * @return value of {@link #modelFilter}
     */
    public String getModelFilter() {
        return String.join(",", modelFilter);
    }

    /**
     * Sets {@link #modelFilter} value
     *
     * @param modelFilter new value of {@link #modelFilter}
     */
    public void setModelFilter(String modelFilter) {
        this.modelFilter = Arrays.stream(modelFilter.split(",")).map(String::trim).filter(StringUtils::isNotNullOrEmpty).collect(Collectors.toList());
    }

    /**
     * Retrieves {@link #brandFilter}
     *
     * @return value of {@link #brandFilter}
     */
    public String getBrandFilter() {
        return String.join(",", brandFilter);
    }

    /**
     * Sets {@link #brandFilter} value
     *
     * @param brandFilter new value of {@link #brandFilter}
     */
    public void setBrandFilter(String brandFilter) {
        this.brandFilter = Arrays.stream(brandFilter.split(",")).map(String::trim).filter(StringUtils::isNotNullOrEmpty).collect(Collectors.toList());
    }

    /**
     * Retrieves {@link #locationUIDFilter}
     *
     * @return value of {@link #locationUIDFilter}
     */
    public String getLocationUIDFilter() {
        return String.join(",", locationUIDFilter);
    }

    /**
     * Sets {@link #locationUIDFilter} value
     *
     * @param locationUIDFilter new value of {@link #locationUIDFilter}
     */
    public void setLocationUIDFilter(String locationUIDFilter) {
        this.locationUIDFilter = Arrays.stream(locationUIDFilter.split(",")).map(String::trim).filter(StringUtils::isNotNullOrEmpty).collect(Collectors.toList());
    }

    /**
     * Retrieves {@link #displayPropertyGroups}
     *
     * @return value of {@link #displayPropertyGroups}
     */
    public String getDisplayPropertyGroups() {
        return String.join(",", displayPropertyGroups);
    }

    /**
     * Sets {@link #displayPropertyGroups} value
     *
     * @param displayPropertyGroups new value of {@link #displayPropertyGroups}
     */
    public void setDisplayPropertyGroups(String displayPropertyGroups) {
        this.displayPropertyGroups = Arrays.stream(displayPropertyGroups.split(",")).map(String::trim).filter(StringUtils::isNotNullOrEmpty).collect(Collectors.toList());;
    }

    @Override
    public void controlProperty(ControllableProperty controllableProperty) throws Exception {
        String deviceId = controllableProperty.getDeviceId();
        Object value = controllableProperty.getValue();
        String property = controllableProperty.getProperty();

        AggregatedDevice device = aggregatedDevices.get(deviceId);
        if (device == null) {
            throw new RuntimeException(String.format("Unable to control the device with id %s: Device is not monitored yet.", deviceId));
        }
        Map<String, String> properties = device.getProperties();
        List<AdvancedControllableProperty> controls = device.getControllableProperties();

        updateControllableProperty(properties, controls, property, value);
        switch (property) {
            case Constant.Properties.SCREEN_RESOLUTION:
            case Constant.Properties.SCREEN_FRAMERATE:
            case Constant.Properties.SCREEN_ORIENTATION:
                updateDeviceResolution(deviceId);
                break;
            case Constant.Properties.SCREENSHOT:
                takeScreenshot(deviceId);
                break;
            case Constant.Properties.VOLUME:
                updateVolume(deviceId, value);
                break;
            case Constant.Properties.REMOTE_CONTROL:
                switchRemoteControl(deviceId, value);
                break;
            case Constant.Properties.REBOOT:
                executePowerAction(PowerAction.SYSTEM_REBOOT, deviceId);
                break;
            case Constant.Properties.APP_RESTART:
                executePowerAction(PowerAction.APP_RESTART, deviceId);
                break;
            case Constant.Properties.APPLET_RELOAD:
                executePowerAction(PowerAction.APPLET_RELOAD, deviceId);
                break;
            case Constant.Properties.APPLET_REFRESH:
                executePowerAction(PowerAction.APPLET_REFRESH, deviceId);
                break;
            case Constant.Properties.DEBUG_APPLET:
            case Constant.Properties.DEBUG_NATIVE:
                updateDebugMode(deviceId, properties);
                break;
            default:
                logDebugMessage("Unknown control operation " + property);
                break;
        }
    }

    @Override
    public void controlProperties(List<ControllableProperty> list) throws Exception {
        if (CollectionUtils.isEmpty(list)) {
            throw new IllegalArgumentException("Controllable properties cannot be null or empty");
        }
        for (ControllableProperty controllableProperty : list) {
            controlProperty(controllableProperty);
        }
    }

    @Override
    public List<Statistics> getMultipleStatistics() {
        List<Statistics> statistics = new ArrayList<>();
        Map<String, String> properties = new HashMap<>();
        Map<String, String> dynamicStatistics = new HashMap<>();

        ExtendedStatistics extendedStatistics = new ExtendedStatistics();
        extendedStatistics.setStatistics(properties);
        extendedStatistics.setDynamicStatistics(dynamicStatistics);
        statistics.add(extendedStatistics);

        properties.put(ADAPTER_VERSION, adapterProperties.getProperty("aggregator.version"));
        properties.put(ADAPTER_BUILD_DATE, adapterProperties.getProperty("aggregator.build.date"));

        long adapterUptime = System.currentTimeMillis() - adapterInitializationTimestamp;
        properties.put(ADAPTER_UPTIME_MIN, String.valueOf(adapterUptime / (1000*60)));
        properties.put(ADAPTER_UPTIME, normalizeUptime(adapterUptime/1000));

        if (lastMonitoringCycleDuration != null) {
            dynamicStatistics.put(LAST_MONITORING_CYCLE_DURATION_S, String.valueOf(lastMonitoringCycleDuration));
        }
        dynamicStatistics.put(MONITORED_DEVICES_TOTAL, String.valueOf(aggregatedDevices.size()));

        //properties.put("RunnerSize(b)", "12824");
        return statistics;
    }

    @Override
    protected void internalInit() throws Exception {
        serviceRunning = true;
        long currentTimestamp = System.currentTimeMillis();
        validRetrieveStatisticsTimestamp = currentTimestamp + retrieveStatisticsTimeOut;
        adapterInitializationTimestamp = currentTimestamp;
        super.internalInit();
    }

    @Override
    protected void internalDestroy() {
        serviceRunning = false;
        if (deviceDataLoader != null) {
            deviceDataLoader.stop();
            deviceDataLoader = null;
        }
        super.internalDestroy();
    }

    @Override
    public List<AggregatedDevice> retrieveMultipleStatistics() throws Exception {
        updateValidRetrieveStatisticsTimestamp();
        if (failedLogin) {
            throw new FailedLoginException("Unable to authorize. Please check SignageOS ClientID and ClientSecret");
        }
        if (failedAPI) {
            throw new RuntimeException("Unable to reach SignageOS API. Please check device communicator configuration");
        }
        nextDevicesCollectionIterationTimestamp = System.currentTimeMillis();
        return new ArrayList<>(aggregatedDevices.values());
    }

    @Override
    public List<AggregatedDevice> retrieveMultipleStatistics(List<String> list) throws Exception {
        return retrieveMultipleStatistics()
                .stream()
                .filter(aggregatedDevice -> list.contains(aggregatedDevice.getDeviceId()))
                .collect(Collectors.toList());
    }

    @Override
    protected void authenticate() throws Exception {
    }

    @Override
    protected HttpHeaders putExtraRequestHeaders(HttpMethod httpMethod, String uri, HttpHeaders headers) throws Exception {
        headers.add("x-auth", String.format("%s:%s", getLogin(), getPassword()));
        return super.putExtraRequestHeaders(httpMethod, uri, headers);
    }

    /**
     * Update controllable property with a new value (for operations like device controls, so adapter and device values are synchronized
     * before the next data collection cycle)
     *
     * @param properties to save new property value to (monitored data)
     * @param controls to save new property value to (device controls)
     * @param property name of the property to update
     * @param value original value of the control, populated by Symphony
     * */
    private void updateControllableProperty(Map<String, String> properties, List<AdvancedControllableProperty> controls, String property, Object value) {
        controls.stream().filter(controllableProperty -> controllableProperty.getName().equals(property)).findAny().ifPresent(controllableProperty -> {
            String controlValue = String.valueOf(value);
            if (controllableProperty.getType() instanceof AdvancedControllableProperty.Switch) {
                properties.put(property, "1".equals(String.valueOf(value)) ? "true" : "false");
            } else {
                properties.put(property, controlValue);
            }
            controllableProperty.setValue(value);
            controllableProperty.setTimestamp(new Date());
        });
    }

    /**
     * Retrieve SignageOS devices metadata and save them into {@link #aggregatedDevices}
     * @throws Exception if API communication errors occur
     * */
    private void fetchDevices() throws Exception {
        Long currentTimestamp = System.currentTimeMillis();
        Long remainingTimeout = currentTimestamp - metadataRetrievalTimestamp;
        if (metadataRetrievalTimestamp != 0L && remainingTimeout < metadataRetrievalInterval) {
            logDebugMessage(String.format("Metadata retrieval is in the cooldown. Remaining timeout is %sms", metadataRetrievalInterval - remainingTimeout));
            return;
        }
        List<AggregatedDevice> devices = new ArrayList<>();
        processPaginatedResponse(buildFilteredUrl(String.format(Constant.URI.DEVICES, requestPageSize)), (page) -> {
            devices.addAll(aggregatedDeviceProcessor.extractDevices(page));
        });
        metadataRetrievalTimestamp = currentTimestamp;

        for(AggregatedDevice device: devices) {
            String deviceUid = device.getDeviceId();
            if (aggregatedDevices.containsKey(deviceUid)) {
                AggregatedDevice existingDevice = aggregatedDevices.get(deviceUid);
                existingDevice.setTimestamp(currentTimestamp);
                existingDevice.getProperties().putAll(device.getProperties());
            } else {
                aggregatedDevices.put(deviceUid, device);
            }
        }
    }

    /**
     * Build SignageOS API URI with filters, configured in adapter configuration: brand, model, applicationType, locationUID
     *
     * @param url original request url
     * @return request url with configured filter query string values
     * */
    private String buildFilteredUrl(String url) {
        String filteredUrl = url;
        if (!brandFilter.isEmpty()) {
            filteredUrl += "&brand=" + String.join(",",brandFilter);
        }
        if (!modelFilter.isEmpty()) {
            filteredUrl += "&model=" + String.join(",", modelFilter);
        }
        if (!applicationTypeFilter.isEmpty()) {
            filteredUrl += "&applicationType=" + String.join(",", applicationTypeFilter);
        }
        if (!locationUIDFilter.isEmpty()) {
            filteredUrl += "&locationUid=" + String.join(",", locationUIDFilter);
        }
        return filteredUrl;
    }

    /**
     * Get uptime values for the device
     *
     * @param device AggregatedDevice instance to fetch data for and to save it to
     * @throws Exception if any API communication error occurs
     * */
    private void fetchUptime(AggregatedDevice device) throws Exception {
        if (!displayPropertyGroups.contains(Constant.PropertyGroups.UPTIME) && !displayPropertyGroups.contains(Constant.PropertyGroups.ALL)) {
            logDebugMessage("Uptime is not included to the list of filtering groups: " + displayPropertyGroups);
            return;
        }
        if (device == null) {
            return;
        }
        logDebugMessage("Fetching uptime information.");
        String deviceId = device.getDeviceId();
        validateAndProcessPropertyRetrieval(deviceId, Constant.PropertyGroups.UPTIME, uptimeRetrievalInterval, () -> {
            Map<String, String> properties = device.getProperties();

            JsonNode response = doGet(String.format(Constant.URI.UPTIME, deviceId), JsonNode.class);
            String uptime = response.at("/uptime").asText();
            String downtime = response.at("/downtime").asText();
            String total = response.at("/total").asText();
            String availabilityPercent = response.at("/availabilityPercent").asText();
            String since = response.at("/since").asText();
            String until = response.at("/until").asText();

            properties.put(Constant.Properties.UPTIME_S, uptime);
            properties.put(Constant.Properties.UPTIME, normalizeUptime(Long.parseLong(uptime)));
            properties.put(Constant.Properties.DOWNTIME_S, downtime);
            properties.put(Constant.Properties.DOWNTIME, normalizeUptime(Long.parseLong(downtime)));
            properties.put(Constant.Properties.UPTIME_TOTAL_S, total);
            properties.put(Constant.Properties.UPTIME_TOTAL, normalizeUptime(Long.parseLong(total)));
            properties.put(Constant.Properties.UPTIME_SINCE, since);
            properties.put(Constant.Properties.UPTIME_UNTIL, until);

            if (historicalProperties.contains(Constant.Properties.UPTIME_AVAILABILITY)) {
                Map<String, String> dynamicProperties = device.getDynamicStatistics();
                dynamicProperties.put(Constant.Properties.UPTIME_AVAILABILITY, availabilityPercent);
            } else {
                properties.put(Constant.Properties.UPTIME_AVAILABILITY, availabilityPercent);
            }
        });
    }

    /**
     * Get latest screenshot values for the device. Number of screenshots is limited by {@link #numberOfScreenshots}
     * and can be configured through adapter configuration properties.
     *
     * @param device AggregatedDevice instance to fetch data for and to save it to
     * @throws Exception if any API communication error occurs
     * */
    private void fetchScreenshots(AggregatedDevice device) throws Exception {
        if (!displayPropertyGroups.contains(Constant.PropertyGroups.SCREENSHOTS) && !displayPropertyGroups.contains(Constant.PropertyGroups.ALL)) {
            logDebugMessage("Screenshots is not included to the list of filtering groups: " + displayPropertyGroups);
            return;
        }
        if (device == null) {
            return;
        }
        logDebugMessage("Fetching screenshots information.");
        String deviceId = device.getDeviceId();
        validateAndProcessPropertyRetrieval(deviceId, Constant.PropertyGroups.SCREENSHOTS, screenshotsRetrievalInterval, () -> {
            JsonNode response = doGet(String.format(Constant.URI.SCREENSHOT, deviceId, numberOfScreenshots), JsonNode.class);
            if (!response.has("items")) {
                logDebugMessage("Unable to retrieve screenshots");
                return;
            }
            Map<String, String> properties = device.getProperties();
            ArrayNode items = (ArrayNode) response.at("/items");
            int i = 1;
            for (JsonNode node: items) {
                properties.put(String.format(Constant.Properties.SCREENSHOT_TAKEN_AT, i), node.at("/takenAt").asText());
                properties.put(String.format(Constant.Properties.SCREENSHOT_URI, i), node.at("/uri").asText());
                i++;
            }
        });
    }

    /**
     * Get action logs for the device. Values are limited by {@link #numberOfActionLogs} and can be configured by
     * adapter configuration properties.
     *
     * @param device AggregatedDevice instance to fetch data for and to save it to
     * @throws Exception if any API communication error occurs
     * */
    private void fetchActionLogs(AggregatedDevice device) throws Exception {
        if (!displayPropertyGroups.contains(Constant.PropertyGroups.ACTION_LOGS) && !displayPropertyGroups.contains(Constant.PropertyGroups.ALL)) {
            logDebugMessage("ActionLogs is not included to the list of filtering groups: " + displayPropertyGroups);
            return;
        }
        if (device == null) {
            return;
        }
        logDebugMessage("Fetching action logs information.");
        String deviceId = device.getDeviceId();
        validateAndProcessPropertyRetrieval(deviceId, Constant.PropertyGroups.ACTION_LOGS, actionLogsRetrievalInterval, () -> {
            JsonNode response = doGet(String.format(Constant.URI.ACTION_LOG, deviceId, numberOfActionLogs), JsonNode.class);
            if (!response.has("items")) {
                logDebugMessage("Unable to retrieve action logs");
                return;
            }
            Map<String, String> properties = device.getProperties();
            ArrayNode items = (ArrayNode) response.at("/items");
            int i = 1;
            for (JsonNode node: items) {
                properties.put(String.format(Constant.Properties.ACTION_LOG_CREATED_AT, i), processStringPropertyValue(node.at("/createdAt").asText()));
                properties.put(String.format(Constant.Properties.ACTION_LOG_FAILED_AT, i), processStringPropertyValue(node.at("/failedAt").asText()));
                properties.put(String.format(Constant.Properties.ACTION_LOG_ORIGINATOR_ACCOUNT_ID, i), processStringPropertyValue(node.at("/originator/account/accountId").asText()));
                properties.put(String.format(Constant.Properties.ACTION_LOG_SUCCEEDED_AT, i), processStringPropertyValue(node.at("/succeededAt").asText()));
                properties.put(String.format(Constant.Properties.ACTION_LOG_TYPE, i), processStringPropertyValue(node.at("/type").asText()));
                i++;
            }
        });
    }

    /**
     * Get device storage values for the device
     *
     * @param device AggregatedDevice instance to fetch data for and to save it to
     * @throws Exception if any API communication error occurs
     * */
    private void fetchStorage(AggregatedDevice device) throws Exception {
        if (!displayPropertyGroups.contains(Constant.PropertyGroups.STORAGE) && !displayPropertyGroups.contains(Constant.PropertyGroups.ALL)) {
            logDebugMessage("Storage is not included to the list of filtering groups: " + displayPropertyGroups);
            return;
        }
        if (device == null) {
            return;
        }
        logDebugMessage("Fetching storage information.");
        String deviceId = device.getDeviceId();

        validateAndProcessPropertyRetrieval(deviceId, Constant.PropertyGroups.STORAGE, storageRetrievalInterval, () -> {
            JsonNode response = doGet(String.format(Constant.URI.STORAGE, deviceId), JsonNode.class);
            String internalCapacity = response.at("/internal/capacity").asText();
            String internalFreeSpace = response.at("/internal/freeSpace").asText();
            String removableCapacity = response.at("/removable/capacity").asText();
            String removableFreeSpace = response.at("/removable/freeSpace").asText();
            String updatedAt = response.at("/updatedAt").asText();

            Map<String, String> properties = device.getProperties();
            Map<String, String> dynamicStatistics = device.getDynamicStatistics();
            boolean internalHistorical = historicalProperties.contains(Constant.Properties.STORAGE_INTERNAL_USED);
            boolean removableHistorical = historicalProperties.contains(Constant.Properties.STORAGE_REMOVABLE_USED);
            if (internalHistorical || removableHistorical) {
                if (dynamicStatistics == null) {
                    dynamicStatistics = new HashMap<>();
                    device.setDynamicStatistics(dynamicStatistics);
                }
            }
            try {
                long internalCapacityValue = Long.parseLong(internalCapacity);
                if (internalCapacityValue > 0) {
                    internalCapacityValue = 100 - (Long.parseLong(internalFreeSpace) * 100 / internalCapacityValue);
                }
                if (internalHistorical) {
                    dynamicStatistics.put(Constant.Properties.STORAGE_INTERNAL_USED, String.valueOf(internalCapacityValue));
                } else {
                    properties.put(Constant.Properties.STORAGE_INTERNAL_USED, String.valueOf(internalCapacityValue));
                }

                long removableCapacityValue = Long.parseLong(removableCapacity);
                if (removableCapacityValue > 0) {
                    removableCapacityValue = 100 - (Long.parseLong(removableFreeSpace) * 100 / removableCapacityValue);
                }
                if (removableHistorical) {
                    dynamicStatistics.put(Constant.Properties.STORAGE_REMOVABLE_USED, String.valueOf(removableCapacityValue));
                } else {
                    properties.put(Constant.Properties.STORAGE_REMOVABLE_USED, String.valueOf(removableCapacityValue));
                }
            } catch (Exception e) {
                logger.error(String.format("Unable to calculate storage used percentage for internal (%s, %s) and external (%s, %s) storages, skipping.",
                        internalFreeSpace, internalCapacity, removableFreeSpace, removableCapacity), e);
            }
            properties.put(Constant.Properties.STORAGE_INTERNAL_CAPACITY, internalCapacity);
            properties.put(Constant.Properties.STORAGE_INTERNAL_AVAILABLE, internalFreeSpace);
            properties.put(Constant.Properties.STORAGE_REMOVABLE_CAPACITY, removableCapacity);
            properties.put(Constant.Properties.STORAGE_REMOVABLE_AVAILABLE, removableFreeSpace);
            properties.put(Constant.Properties.STORAGE_LAST_UPDATED, updatedAt);
        });
    }

    /**
     * Get VPN values for the device
     *
     * @param device AggregatedDevice instance to fetch data for and to save it to
     * @throws Exception if any API communication error occurs
     * */
    private void fetchVPN(AggregatedDevice device) throws Exception {
        if (!displayPropertyGroups.contains(Constant.PropertyGroups.VPN) && !displayPropertyGroups.contains(Constant.PropertyGroups.ALL)) {
            logDebugMessage("VPN is not included to the list of filtering groups: " + displayPropertyGroups);
            return;
        }
        if (device == null) {
            return;
        }
        logDebugMessage("Fetching VPN information.");
        String deviceId = device.getDeviceId();
        validateAndProcessPropertyRetrieval(deviceId, Constant.PropertyGroups.VPN, vpnRetrievalInterval, () -> {
            Map<String, String> properties = device.getProperties();
            JsonNode response = doGet(String.format(Constant.URI.VPN, deviceId), JsonNode.class);
            String vpnEnabled = response.at("/items/0/enabled").asText();
            properties.put(Constant.Properties.VPN_ENABLED, processStringPropertyValue(vpnEnabled));
        });
    }

    /**
     * Get telemetry information for the device
     *
     * @param device AggregatedDevice instance to fetch data for and to save it to
     * @throws Exception if any API communication error occurs
     * */
    private void fetchTelemetryRecords(AggregatedDevice device) throws Exception {
        if (device == null) {
            return;
        }
        logDebugMessage("Fetching telemetry information.");
        String deviceId = device.getDeviceId();
        validateAndProcessPropertyRetrieval(deviceId, Constant.PropertyGroups.TELEMETRY, telemetrySettingsRetrievalInterval, () -> {
            for (TelemetrySetting setting: TelemetrySetting.values()) {
                String telemetrySettingName = setting.name();
                try {
                    JsonNode response = doGet(String.format(Constant.URI.TELEMETRY, deviceId, telemetrySettingName), JsonNode.class);
                    populateTelemetryDetails(setting, response, device);
                } catch (Exception e) {
                    logger.error(String.format("Unable to retrieve %s telemetry settings for device with id %s", telemetrySettingName, deviceId));
                }
            }
        });
    }

    /**
     * Get temperature values for the device. The value can be either regular monitored property, or historical, based on {@link #historicalProperties}
     *
     * @param value json value of original telemetry response
     * @param device AggregatedDevice instance to fetch data for and to save it to
     * */
    private void populateDeviceTemperature(JsonNode value, AggregatedDevice device) {
        if (!displayPropertyGroups.contains(Constant.PropertyGroups.TELEMETRY_TEMPERATURE) && !displayPropertyGroups.contains(Constant.PropertyGroups.ALL)) {
            logDebugMessage("TelemetryTemperature is not included to the list of filtering groups: " + displayPropertyGroups);
            return;
        }
        logDebugMessage("Processing telemetry temperature information.");
        String volume = value.at("/data/temperature").asText();
        if (StringUtils.isNullOrEmpty(volume)) {
            logDebugMessage("Unable to get device temperature: temperature cannot be retrieved.");
            return;
        }
        if (historicalProperties.contains(Constant.Properties.TEMPERATURE)) {
            Map<String, String> dynamicStatistics = device.getDynamicStatistics();
            if (dynamicStatistics == null) {
                dynamicStatistics = new HashMap<>();
                device.setDynamicStatistics(dynamicStatistics);
            }
            dynamicStatistics.put(Constant.Properties.TEMPERATURE, volume);
        } else {
            Map<String, String> deviceProperties = device.getProperties();
            deviceProperties.put(Constant.Properties.TEMPERATURE, volume);
        }
    }

    /**
     * Get volume level for the device
     *
     * @param value json value of original telemetry response
     * @param deviceProperties device properties to save volume to
     * @param deviceControls device controls to add volume slider to
     * */
    private void populateDeviceVolume(JsonNode value, Map<String, String> deviceProperties, List<AdvancedControllableProperty> deviceControls) {
        if (!displayPropertyGroups.contains(Constant.PropertyGroups.TELEMETRY_VOLUME) && !displayPropertyGroups.contains(Constant.PropertyGroups.ALL)) {
            logDebugMessage("TelemetryVolume is not included to the list of filtering groups: " + displayPropertyGroups);
            return;
        }
        logDebugMessage("Processing telemetry volume information.");
        String volume = value.at("/data/volume").asText();
        if (StringUtils.isNullOrEmpty(volume)) {
            logDebugMessage("Unable to get device volume level: volume cannot be retrieved.");
            return;
        }
        deviceProperties.put(Constant.Properties.VOLUME, volume);
        addControllableProperty(deviceControls, createSlider(Constant.Properties.VOLUME, 0.0f, 100.0f, Float.valueOf(volume)));
    }

    /**
     * Get brightness for the device. SignageAPI control endpoint is asynchronous, so this property is only monitored.
     *
     * @param value json value of original telemetry response
     * @param deviceProperties to save property to
     * */
    private void populateDeviceBrightness(JsonNode value, Map<String, String> deviceProperties) {
        if (!displayPropertyGroups.contains(Constant.PropertyGroups.TELEMETRY_BRIGHTNESS) && !displayPropertyGroups.contains(Constant.PropertyGroups.ALL)) {
            logDebugMessage("TelemetryBrightness is not included to the list of filtering groups: " + displayPropertyGroups);
            return;
        }
        logDebugMessage("Processing telemetry brightness information.");
        String brightness = value.at("/data/brightness").asText();
        if (StringUtils.isNullOrEmpty(brightness)) {
            logDebugMessage("Unable to get device brightness level: brightness cannot be retrieved.");
            return;
        }
        deviceProperties.put(Constant.Properties.BRIGHTNESS, brightness);
    }

    /**
     * Get device resolution related values. Height, width, framerate and orientation are changed by a single command afterwards
     * and must be provided together.
     *
     * @param value json value of original telemetry response
     * @param deviceProperties device property map to save properties to
     * @param deviceControls device controls list to save resolution controls to
     * */
    private void populateDeviceResolution(JsonNode value, Map<String, String> deviceProperties, List<AdvancedControllableProperty> deviceControls) {
        if (!displayPropertyGroups.contains(Constant.PropertyGroups.TELEMETRY_RESOLUTION) && !displayPropertyGroups.contains(Constant.PropertyGroups.ALL)) {
            logDebugMessage("TelemetryResolution is not included to the list of filtering groups: " + displayPropertyGroups);
            return;
        }
        logDebugMessage("Processing telemetry resolution information.");
        String height = value.at("/data/height").asText();
        String width = value.at("/data/width").asText();
        String framerate = value.at("/data/framerate").asText();
        if (StringUtils.isNullOrEmpty(height) && StringUtils.isNullOrEmpty(width) && StringUtils.isNullOrEmpty(framerate)) {
            logDebugMessage("Unable to get device resolution: resolution cannot be retrieved.");
            return;
        }
        String resolution = String.format("%sx%s", width, height);
        List<String> resolutionsList = new ArrayList<>(Constant.Properties.RESOLUTION_OPTIONS);
        if (!resolutionsList.contains(resolution)) {
            resolutionsList.add(resolution);
        }
        deviceProperties.put(Constant.Properties.SCREEN_RESOLUTION, resolution);
        addControllableProperty(deviceControls, createDropdown(Constant.Properties.SCREEN_RESOLUTION, resolutionsList, resolution));

        deviceProperties.put(Constant.Properties.SCREEN_FRAMERATE, framerate);
        addControllableProperty(deviceControls, createNumeric(Constant.Properties.SCREEN_FRAMERATE, framerate));
    }

    /**
     * Get device orientation value. Height, width, framerate and orientation are changed by a single command afterwards
     * and must be provided together.
     *
     * @param value json value of original telemetry response
     * @param deviceProperties device property map to save properties to
     * @param deviceControls device controls list to save resolution controls to
     * */
    private void populateDeviceOrientation(JsonNode value, Map<String, String> deviceProperties, List<AdvancedControllableProperty> deviceControls) {
        if (!displayPropertyGroups.contains(Constant.PropertyGroups.TELEMETRY_RESOLUTION) && !displayPropertyGroups.contains(Constant.PropertyGroups.ALL)) {
            logDebugMessage("TelemetryOrientation is not included to the list of filtering groups: " + displayPropertyGroups);
            return;
        }
        logDebugMessage("Processing telemetry orientation information.");
        String orientation = value.at("/data/orientation").asText();
        if (StringUtils.isNullOrEmpty(orientation)) {
            logDebugMessage("Unable to get device orientation: orientation cannot be retrieved.");
            return;
        }
        deviceProperties.put(Constant.Properties.SCREEN_ORIENTATION, orientation);
        addControllableProperty(deviceControls, createDropdown(Constant.Properties.SCREEN_ORIENTATION, ORIENTATION_OPTIONS, orientation));
    }

    /**
     * Get device remote control setting.
     *
     * @param value json value of original telemetry response
     * @param deviceProperties device property map to save properties to
     * @param deviceControls device controls list to save resolution controls to
     * */
    private void populateDeviceRemoteControl(JsonNode value, Map<String, String> deviceProperties, List<AdvancedControllableProperty> deviceControls) {
        if (!displayPropertyGroups.contains(Constant.PropertyGroups.TELEMETRY_REMOTE_CONTROL) && !displayPropertyGroups.contains(Constant.PropertyGroups.ALL)) {
            logDebugMessage("TelemetryRemoteControl is not included to the list of filtering groups: " + displayPropertyGroups);
            return;
        }
        logDebugMessage("Processing telemetry remote control information.");
        String remoteControl = value.at("/data/enabled").asText();
        if (StringUtils.isNullOrEmpty(remoteControl)) {
            logDebugMessage("Unable to get device remoteControl: remoteControl cannot be retrieved.");
            return;
        }
        deviceProperties.put(Constant.Properties.REMOTE_CONTROL, remoteControl);
        addControllableProperty(deviceControls, createSwitch(Constant.Properties.REMOTE_CONTROL, "true".equals(remoteControl) ? 1 : 0));
    }

    /**
     * Get device front display version and versionNumber.
     *
     * @param value json value of original telemetry response
     * @param deviceProperties device property map to save properties to
     * */
    private void populateDeviceFrontDisplayVersion(JsonNode value, Map<String, String> deviceProperties) {
        if (!displayPropertyGroups.contains(Constant.PropertyGroups.TELEMETRY_FRONT_DISPLAY_VERSION) && !displayPropertyGroups.contains(Constant.PropertyGroups.ALL)) {
            logDebugMessage("TelemetryFrontDisplayVersion is not included to the list of filtering groups: " + displayPropertyGroups);
            return;
        }
        logDebugMessage("Processing telemetry front display version information.");
        String version = value.at("/data/version").asText();
        String versionNumber = value.at("/data/versionNumber").asText();
        if (StringUtils.isNullOrEmpty(version) && StringUtils.isNullOrEmpty(versionNumber)) {
            logDebugMessage("Unable to get device front display version: front display version cannot be retrieved.");
            return;
        }
        deviceProperties.put(Constant.Properties.FRONT_DISPLAY_VERSION, version);
        deviceProperties.put(Constant.Properties.FRONT_DISPLAY_VERSION_NUMBER, versionNumber);
    }

    /**
     * Get device core application level.
     *
     * @param value json value of original telemetry response
     * @param deviceProperties device property map to save properties to
     * */
    private void populateDeviceApplicationVersion(JsonNode value, Map<String, String> deviceProperties) {
        if (!displayPropertyGroups.contains(Constant.PropertyGroups.TELEMETRY_APPLICATION_VERSION) && !displayPropertyGroups.contains(Constant.PropertyGroups.ALL)) {
            logDebugMessage("TelemetryApplicationVersion is not included to the list of filtering groups: " + displayPropertyGroups);
            return;
        }
        logDebugMessage("Processing telemetry application version information.");
        String version = value.at("/data/version").asText();
        String versionNumber = value.at("/data/versionNumber").asText();
        if (StringUtils.isNullOrEmpty(version) && StringUtils.isNullOrEmpty(versionNumber)) {
            logDebugMessage("Unable to get device application version: application version cannot be retrieved.");
            return;
        }
        deviceProperties.put(Constant.Properties.APPLICATION_VERSION, version);
        deviceProperties.put(Constant.Properties.APPLICATION_VERSION_NUMBER, versionNumber);
    }

    /**
     * Get device debug configuration status.
     *
     * @param value json value of original telemetry response
     * @param deviceProperties device property map to save properties to
     * @param controls device controls list to save resolution controls to
     * */
    private void populateDebugInformation(JsonNode value, Map<String, String> deviceProperties, List<AdvancedControllableProperty> controls) {
        if (!displayPropertyGroups.contains(Constant.PropertyGroups.TELEMETRY_DEBUG) && !displayPropertyGroups.contains(Constant.PropertyGroups.ALL)) {
            logDebugMessage("TelemetryDebug is not included to the list of filtering groups: " + displayPropertyGroups);
            return;
        }
        logDebugMessage("Processing telemetry debug information.");
        String appletEnabled = value.at("/data/appletEnabled").asText();
        String nativeEnabled = value.at("/data/nativeEnabled").asText();
        if (StringUtils.isNullOrEmpty(appletEnabled) && StringUtils.isNullOrEmpty(nativeEnabled)) {
            logDebugMessage("Unable to get device debug information: debug information cannot be retrieved.");
            return;
        }
        deviceProperties.put(Constant.Properties.DEBUG_APPLET, appletEnabled);
        addControllableProperty(controls, createSwitch(Constant.Properties.DEBUG_APPLET, Boolean.parseBoolean(appletEnabled) ? 1 : 0));

        deviceProperties.put(Constant.Properties.DEBUG_NATIVE, nativeEnabled);
        addControllableProperty(controls, createSwitch(Constant.Properties.DEBUG_NATIVE, Boolean.parseBoolean(nativeEnabled) ? 1 : 0));
    }

    /**
     * Get device time settings.
     *
     * @param value json value of original telemetry response
     * @param deviceProperties device property map to save properties to
     * */
    private void populateDateTimeInformation(JsonNode value, Map<String, String> deviceProperties) {
        if (!displayPropertyGroups.contains(Constant.PropertyGroups.TELEMETRY_DATE_TIME) && !displayPropertyGroups.contains(Constant.PropertyGroups.ALL)) {
            logDebugMessage("TelemetryDateTime is not included to the list of filtering groups: " + displayPropertyGroups);
            return;
        }
        logDebugMessage("Processing telemetry date time information.");
        String timezone = value.at("/data/timezone").asText();
        String ntpServer = value.at("/data/ntpServer").asText();
        if (StringUtils.isNullOrEmpty(timezone) && StringUtils.isNullOrEmpty(ntpServer)) {
            logDebugMessage("Unable to get device time information: device time cannot be retrieved.");
            return;
        }
        deviceProperties.put(Constant.Properties.TIMEZONE, timezone);
        deviceProperties.put(Constant.Properties.NTP_SERVER, ntpServer);
    }

    /**
     * Get online status of the device. This endpoint is always used, no matter what {@link #displayPropertyGroups} contents are
     *
     * @param value json value of original telemetry response
     * @param device AggregatedDevice instance to set onlineStatus for
     * */
    private void populateOnlineStatusInformation(JsonNode value, AggregatedDevice device) {
        logDebugMessage("Retrieving online status for device " + device.getDeviceId());

        String online = value.at("/data/online").asText();
        if (StringUtils.isNullOrEmpty(online)) {
            logDebugMessage("Unable to get device online status: online status cannot be retrieved.");
            return;
        }
        device.setDeviceOnline("true".equalsIgnoreCase(online));
    }

    /**
     * Get device proxy information.
     *
     * @param value json value of original telemetry response
     * @param deviceProperties device property map to save properties to
     * */
    private void populateProxyInformation(JsonNode value, Map<String, String> deviceProperties) {
        if (!displayPropertyGroups.contains(Constant.PropertyGroups.TELEMETRY_PROXY) && !displayPropertyGroups.contains(Constant.PropertyGroups.ALL)) {
            logDebugMessage("TelemetryProxy is not included to the list of filtering groups: " + displayPropertyGroups);
            return;
        }
        logDebugMessage("Processing telemetry proxy information.");
        String enabled = value.at("/data/enabled").asText();
        String uri = value.at("/data/uri").asText();
        if (StringUtils.isNullOrEmpty(enabled) && StringUtils.isNullOrEmpty(uri)) {
            logDebugMessage("Unable to get device proxy information: proxy information cannot be retrieved.");
            return;
        }
        deviceProperties.put(Constant.Properties.PROXY_ENABLED, enabled);
        deviceProperties.put(Constant.Properties.PROXY_URI, uri);
    }

    /**
     * Get device wifi strength level.
     *
     * @param value json value of original telemetry response
     * @param deviceProperties device property map to save properties to
     * */
    private void populateWiFiStrengthInformation(JsonNode value, Map<String, String> deviceProperties) {
        if (!displayPropertyGroups.contains(Constant.PropertyGroups.TELEMETRY_WIFI_STRENGTH) && !displayPropertyGroups.contains(Constant.PropertyGroups.ALL)) {
            logDebugMessage("TelemetryWiFiStrength is not included to the list of filtering groups: " + displayPropertyGroups);
            return;
        }
        logDebugMessage("Processing telemetry wifi strength information.");
        String wifiStrength = value.at("/data/strength").asText();
        if (StringUtils.isNullOrEmpty(wifiStrength)) {
            logDebugMessage("Unable to get device wifi strength: wifi strength version cannot be retrieved.");
            return;
        }
        deviceProperties.put(Constant.Properties.WIFI_STRENGTH, wifiStrength);
    }

    /**
     * Route telemetry data filtering, based on {@link TelemetrySetting} type.
     *
     * @param setting instance of {@link TelemetrySetting} to properly type and filter the telemetry response
     * @param value json value of original telemetry response
     * @param device AggregatedDevice instance to save data to
     * */
    private void populateTelemetryDetails(TelemetrySetting setting, JsonNode value, AggregatedDevice device) {
        if (value == null || value.at("/data").isEmpty()) {
            logDebugMessage(String.format("Unable to retrieve %s setting for device with id %s", setting, device.getDeviceId()));
            return;
        }
        Map<String, String> deviceProperties = device.getProperties();
        List<AdvancedControllableProperty> controls = device.getControllableProperties();

        switch (setting) {
            case TEMPERATURE:
                populateDeviceTemperature(value, device);
                break;
            case VOLUME:
                populateDeviceVolume(value, deviceProperties, controls);
                break;
            case BRIGHTNESS:
                populateDeviceBrightness(value, deviceProperties);
                break;
            case RESOLUTION:
                populateDeviceResolution(value, deviceProperties, controls);
                break;
            case ORIENTATION:
                populateDeviceOrientation(value, deviceProperties, controls);
                break;
            case REMOTE_CONTROL:
                populateDeviceRemoteControl(value, deviceProperties, controls);
                break;
            case APPLICATION_VERSION:
                populateDeviceApplicationVersion(value, deviceProperties);
                break;
            case FRONT_DISPLAY_VERSION:
                populateDeviceFrontDisplayVersion(value, deviceProperties);
                break;
            case DEBUG:
                populateDebugInformation(value, deviceProperties, controls);
                return;
            case DATETIME:
                populateDateTimeInformation(value, deviceProperties);
                break;
            case ONLINE_STATUS:
                populateOnlineStatusInformation(value, device);
                break;
            case PROXY:
                populateProxyInformation(value, deviceProperties);
                break;
            case WIFI_STRENGTH:
                populateWiFiStrengthInformation(value, deviceProperties);
                break;
            default:
                logDebugMessage("Unknown telemetry setting type: " + setting);
                break;
        }
    }

    /**
     * Add controllable property to device controls list, to avoid duplicates - the method will check controls by name
     * and only add a new control, if it hasnt been added to the list yet. Otherwise, existing control will be updated and
     * contain new values and timestamp.
     *
     * @param controllableProperty property to add to controls
     * @param controls list of controls to add new control to/edit the control in
     * */
    private void addControllableProperty(List<AdvancedControllableProperty> controls, AdvancedControllableProperty controllableProperty) {
        Optional<AdvancedControllableProperty> existingControl = controls.stream().filter(control -> control.getName().equals(controllableProperty.getName())).findAny();
        existingControl.ifPresent(control -> {
            control.setValue(controllableProperty.getValue());
            control.setTimestamp(controllableProperty.getTimestamp());
        });
        if (!existingControl.isPresent()) {
            controls.add(controllableProperty);
        }
    }
    /**
     * {@inheritDoc}
     * <p>
     * Additional interceptor to RestTemplate that checks the amount of requests left for metrics endpoints
     */
    @Override
    protected RestTemplate obtainRestTemplate() throws Exception {
        RestTemplate restTemplate = super.obtainRestTemplate();

        List<ClientHttpRequestInterceptor> interceptors = restTemplate.getInterceptors();
        if (!interceptors.contains(headerInterceptor))
            interceptors.add(headerInterceptor);

        final HttpClient httpClient = HttpClients.custom()
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setCookieSpec(CookieSpecs.STANDARD).build())
                .build();

        restTemplate.setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(httpClient)
        );
        return restTemplate;
    }

    /**
     * Process debug mode controllable property action.
     *
     * @param deviceId to identify the device this command has be executed for
     * @param properties device properties to fetch command values from
     * */
    private void updateDebugMode(String deviceId, Map<String, String> properties) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("appletEnabled", Boolean.parseBoolean(properties.get(DEBUG_APPLET)));
        request.put("nativeEnabled", Boolean.parseBoolean(properties.get(DEBUG_NATIVE)));
        doPut(String.format(Constant.URI.DEBUG, deviceId), request);
    }

    /**
     * Process device screenshot request.
     *
     * @param deviceId to identify the device this command has be executed for
     * */
    private void takeScreenshot(String deviceId) throws Exception {
        doPost(String.format(Constant.URI.SCREENSHOT_COMMAND, deviceId), null);
    }

    /**
     * Process device power action, based on {@link PowerAction} type provided
     *
     * @param powerAction type of power action to execute
     * @param deviceId to identify the device this command has be pushed for
     * */
    private void executePowerAction(PowerAction powerAction, String deviceId) throws Exception {
        Map<String, String> command = new HashMap<>();
        command.put("devicePowerAction", powerAction.name());
        doPost(String.format(Constant.URI.POWER_ACTION, deviceId), command);
    }

    /**
     * Update device resolution, orientation and framerate. These parameters must be pushed together.
     *
     * @param deviceId to identify the device resolution is updated for
     * */
    private void updateDeviceResolution(String deviceId) throws Exception {
        AggregatedDevice device = aggregatedDevices.get(deviceId);
        if (device == null) {
            return;
        }
        Map<String, String> properties = device.getProperties();
        String[] resolution = properties.get(Constant.Properties.SCREEN_RESOLUTION).split("x");
        Integer width = Integer.parseInt(resolution[0]);
        Integer height = Integer.parseInt(resolution[1]);
        Integer framerate = Integer.parseInt(properties.get(Constant.Properties.SCREEN_FRAMERATE));
        String orientation = properties.get(Constant.Properties.SCREEN_ORIENTATION);

        Map<String, Object> request = new HashMap<>();
        Map<String, Object> size = new HashMap<>();
        size.put("width", width);
        size.put("height", height);
        request.put("size", size);
        request.put("orientation", orientation);
        request.put("framerate", framerate);

        doPut(String.format(Constant.URI.RESOLUTION, deviceId), request);
    }

    /**
     * Switch remote control mode. In order to do that, the opposite "locked" value is pushed (Locked = Kiosk Mode)
     * locked = RC disabled
     * unlocked = RC enabled
     *
     * @param deviceId to execute the command for
     * @param status new remote control value
     * */
    private void switchRemoteControl(String deviceId, Object status) throws Exception {
        Map<String, Boolean> request = new HashMap<>();
        // enabled:true is locked:false and vice-versa
        logDebugMessage("Switching remoteControl state to " + status);
        request.put("locked", !"1".equals(String.valueOf(status)));
        logDebugMessage("Switching remoteControl state " + request);
        doPut(String.format(Constant.URI.REMOTE_CONTROL, deviceId), request);
    }

    /**
     * Set volume level of the device
     *
     * @param deviceId to update volume level for
     * @param value volume level value to set
     * */
    private void updateVolume(String deviceId, Object value) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("volume", value);
        doPut(String.format(Constant.URI.VOLUME, deviceId), request);
    }

    /**
     * Process the response that implies possibility of having more than 1 page in total.
     * Whenever there are more pages - next_page_token property is used for the next page reference
     *
     * @param url to fetch
     * @param processor interface that grants correct page response processing
     * @throws Exception if communication error occurs
     * */
    private void processPaginatedResponse(String url, PaginatedResponseProcessor processor) throws Exception {
        JsonNode devices;
        boolean hasNextPage = true;
        String nextPageLink = null;
        while(hasNextPage) {
            logDebugMessage(String.format("Receiving page with link: %s", nextPageLink));
            if (StringUtils.isNullOrEmpty(nextPageLink)) {
                devices = doGetWithRetry(url);
            } else {
                devices = doGetWithRetry(nextPageLink);
            }
            if (devices == null) {
                hasNextPage = false;
                continue;
            }
            String nextPageLinkHeader = devices.at("/nextPageLink").asText();
            JsonNode pageContents = devices.at("/items");

            if (StringUtils.isNullOrEmpty(nextPageLinkHeader)) {
                processor.process(pageContents);
                return;
            }
            int start = nextPageLinkHeader.indexOf('<');
            int end = nextPageLinkHeader.indexOf('>');
            if (start == -1 || end == -1 || start >= end) {
                logDebugMessage(String.format("Unable to retrieve next page link: Link header is invalid {%s}", nextPageLinkHeader));
                processor.process(pageContents);
                return;
            }
            nextPageLink = URLDecoder.decode(nextPageLinkHeader.substring(start + 1, end));
            hasNextPage = StringUtils.isNotNullOrEmpty(nextPageLink);
            processor.process(pageContents);
        }
    }

    /**
     * If addressed too frequently, SignageOS API may respond with 429 code, meaning that the call rate per second was reached.
     * Normally it would rarely happen due to the request rate limit, but when it does happen - adapter must retry the
     * attempts of retrieving needed information. This method retries up to 10 times with 500ms timeout in between
     *
     * @param url to retrieve data from
     * @return JsonNode response body
     * @throws Exception if a communication error occurs
     */
    private JsonNode doGetWithRetry(String url) throws Exception {
        int retryAttempts = 0;
        Exception lastError = null;
        boolean criticalError = false;
        while (retryAttempts++ < 10 && serviceRunning) {
            try {
                return doGet(url, JsonNode.class);
            } catch (CommandFailureException e) {
                //TODO propagate 429 on top, so API Error is reported? (make optional)
                lastError = e;
                if (e.getStatusCode() != 429) {
                    // Might be 401, 403 or any other error code here so the code will just get stuck
                    // cycling this failed request until it's fixed. So we need to skip this scenario.
                    criticalError = true;
                    logger.error(String.format("SignageOS API error %s while retrieving %s data", e.getStatusCode(), url), e);
                    break;
                }
            } catch (FailedLoginException fle) {
                lastError = fle;
                criticalError = true;
                break;
            } catch (Exception e) {
                lastError = e;
                // if service is running, log error
                if (serviceRunning) {
                    criticalError = true;
                    logger.error(String.format("SignageOS API error while retrieving %s data", url), e);
                }
                break;
            }
            TimeUnit.MILLISECONDS.sleep(200);
        }

        if (retryAttempts == 10 && serviceRunning || criticalError) {
            // if we got here, all 10 attempts failed, or this is a login error that doesn't imply retry attempts
            if (lastError instanceof CommandFailureException) {
                int code = ((CommandFailureException)lastError).getStatusCode();
                if (code == HttpStatus.UNAUTHORIZED.value() || code == HttpStatus.FORBIDDEN.value()) {
                    return null;
                }
            } else if (lastError instanceof FailedLoginException) {
                throw lastError;
            } else {
                logger.error(String.format("Error occurred during [%s] operation execution.", url), lastError);
            }
        }
        return null;
    }


    /**
     * Logging debug message with checking if it's enabled first
     *
     * @param message to log
     * */
    private void logDebugMessage(String message) {
        if (logger.isDebugEnabled()) {
            logger.debug(message);
        }
    }

    /**
     * Update the status of the device.
     * The device is considered as paused if did not receive any retrieveMultipleStatistics()
     * calls during {@link SignageOSCommunicator#validRetrieveStatisticsTimestamp}
     */
    private synchronized void updateAggregatorStatus() {
        // If the adapter is destroyed out of order, we need to make sure the device isn't paused here
        if (validRetrieveStatisticsTimestamp > 0L) {
            devicePaused = validRetrieveStatisticsTimestamp < System.currentTimeMillis();
        } else {
            devicePaused = false;
        }
    }

    /**
     * Update statistics retrieval timestamp
     * */
    private synchronized void updateValidRetrieveStatisticsTimestamp() {
        validRetrieveStatisticsTimestamp = System.currentTimeMillis() + retrieveStatisticsTimeOut;
        updateAggregatorStatus();
    }

    /**
     * Uptime is received in seconds, need to normalize it and make it human readable, like
     * 1 day(s) 5 hour(s) 12 minute(s) 55 minute(s)
     * Incoming parameter is may have a decimal point, so in order to safely process this - it's rounded first.
     * We don't need to add a segment of time if it's 0.
     *
     * @param uptimeSeconds value in seconds
     * @return string value of format 'x day(s) x hour(s) x minute(s) x minute(s)'
     */
    private String normalizeUptime(long uptimeSeconds) {
        StringBuilder normalizedUptime = new StringBuilder();

        long seconds = uptimeSeconds % 60;
        long minutes = uptimeSeconds % 3600 / 60;
        long hours = uptimeSeconds % 86400 / 3600;
        long days = uptimeSeconds / 86400;

        if (days > 0) {
            normalizedUptime.append(days).append(" day(s) ");
        }
        if (hours > 0) {
            normalizedUptime.append(hours).append(" hour(s) ");
        }
        if (minutes > 0) {
            normalizedUptime.append(minutes).append(" minute(s) ");
        }
        if (seconds > 0) {
            normalizedUptime.append(seconds).append(" second(s)");
        }
        return normalizedUptime.toString().trim();
    }

    /**
     * Process string property value and return 'N/A' if the value is empty
     *
     * @param value original property value
     * @return 'N/A' if the value is blank, original value otherwise
     * */
    private String processStringPropertyValue(String value) {
        if(StringUtils.isNullOrEmpty(value)) {
            return "N/A";
        }
        return value;
    }

    /**
     * Process data retrieval operation, by checking the eligibility of the operation first (based on saved operation timestamps and configured intervals),
     * executing an operation and saving new timestamp for an operation
     *
     * @param deviceId to execute operation and save caches for
     * @param propertyGroup group of properties that must be retrieved during current operation execution
     * @param interval amount of time that must pass before executing new operation of the same type
     * @param processor operation to be executed
     * @throws Exception if API communication errors occur
     * */
    private void validateAndProcessPropertyRetrieval(String deviceId, String propertyGroup, Integer interval, PropertyGroupRetrievalProcessor processor) throws Exception {
        ConcurrentHashMap<String, Long> timestamps = propertyGroupsRetrievedTimestamps.get(deviceId);
        Long groupRetrievalTimestamp = timestamps.get(propertyGroup);
        if (groupRetrievalTimestamp == null) {
            groupRetrievalTimestamp = 0L;
        }
        Long currentTimestamp = System.currentTimeMillis();
        Long remainingTimeout = currentTimestamp - groupRetrievalTimestamp;
        if (groupRetrievalTimestamp != 0L && remainingTimeout < interval) {
            logDebugMessage(String.format("%s retrieval for device with id %s is in the cooldown. Remaining timeout is %s", propertyGroup, deviceId, interval - remainingTimeout));
            return;
        }
        processor.process();
        timestamps.put(propertyGroup, System.currentTimeMillis());
    }
}
