# CLAUDE-SIGNATURES.md — Method Signatures Reference

> API lookup reference. Use this when writing code that calls or extends any class in this project.

---

## `ScreeningSchedule21Application`
```java
public static void main(String[] args)
@Bean public ModelMapper modelMapper()
```

## `AlertManager`
```java
AlertManager(AlertRepository alertRepo)
@PostConstruct public void init()
public void loadData()
public boolean isExist(String customerId, String policyNo)
```

## `ArchivalService`
```java
ArchivalService(JdbcTemplate jdbcTemplate, ConfigLoader configLoader)
@Scheduled(cron = "${application.config.screening.archival-cron:0 0 2 1 * *}")
public void archiveOldExceptions()
```

## `ScreeningProcessor`
```java
ScreeningProcessor(ScreeningScheduleRepository, SanctionTypeRepository, SanctionStrategyBuilder,
                   AlertManager, @Qualifier("oracleJdbcTemplate") JdbcTemplate,
                   ScreeningGovernor, ConfigLoader, ScreeningMetrics,
                   ScreeningRunRepository, ScreeningExceptionRepository)
@PostConstruct public void init()
public void process()
private void processInChunks(String queryStr, SanctionScreeningStrategy strategy, Long scenarioId)
private void updateRunRecord(String runId, LocalDateTime runStartedAt, Throwable ex)
```

## `ScreeningGovernor`
```java
ScreeningGovernor(ConfigLoader)
@PostConstruct public void init()
public void register(Stage stage, ExecutorService executor)
@Override public void report(Stage stage, PipelineSignal signal)
public void acquireChunkSlot() throws InterruptedException
public void releaseChunkSlot()
public ChunkBuffer getChunkBuffer()
public ScoringBuffer getScoringBuffer()
public AlertBuffer getAlertBuffer()
public Map<Stage, PipelineSignal> getStageSignals()
public int getProgressPercentage()
public void submitForScoring(CustomerInfo, List<ScreeningCandidate>, ScreeningMode, long scenarioId)
        throws InterruptedException
public void submitForAlert(AlertBuffer.AlertPayload) throws InterruptedException
@PreDestroy public void onDestroy()
```

## `ScoringConsumer`
```java
ScoringConsumer(ScreeningGovernor, MatchRateClient, ConfigLoader, DeadLetterService, ScreeningMetrics)
@PostConstruct public void init()
private void drain()
private void processPayload(ScoringPayload payload)
private void logMatchResults(String clientId, String customerName, List<CandidateResult>, double highestScore)
```

## `AlertConsumer`
```java
AlertConsumer(ScreeningGovernor, AlertEntityRepository, ScreeningMatchRepository,
              ConfigLoader, ScreeningMetrics)
@PostConstruct public void init()
private void drain()
private void persistBatch(List<AlertPayload> batch)
AlertEntity buildAlertEntity(AlertPayload, Map<Long, ScreeningScheduleEntity> scenarioCache)
List<ScreeningMatchEntity> buildMatchEvidence(AlertEntity savedAlert, AlertPayload)
```

## `DeadLetterService`
```java
DeadLetterService(ScreeningGovernor, AlertConsumer, AlertEntityRepository)
@PostConstruct public void init()
public void handleMissedAlert(AlertPayload payload)
```

## `ScreeningExceptionWriter`
```java
ScreeningExceptionWriter(ScreeningExceptionRepository)
public ExceptionContext writeSkipped(CustomerInfo, String runId, NameNormalizer.Result)
public ScreeningExceptionEntity startException(CustomerInfo, String runId, NameNormalizer.Result,
                                               ScreeningMode, String screenName)
public void setListResult(ScreeningExceptionEntity, String type, List<ScreeningCandidate>, ScreeningMode)
public void setListFailed(ScreeningExceptionEntity, String type, String reason, String details)
public void commit(ScreeningExceptionEntity)
static boolean isNotApplicable(String type, ScreeningMode mode)
```

## `SanctionStrategyBuilder`
```java
SanctionStrategyBuilder(SolrQueryExecutor, ScreeningExceptionWriter)
public void setRunId(String runId)
public Map<Long, SanctionScreeningStrategy> build(List<SanctionTypeView> sanctionTypes)
private SanctionScreeningStrategy composeStrategy(List<String> types)
private Function<CustomerInfo, List<ScreeningCandidate>> resolveStrategy(String type, ScreeningMode mode)
```

## `SanctionScreeningStrategy`
```java
ScreeningResult execute(CustomerInfo customer)  // functional interface
```

## `AdvancedScreeningSolrQueryBuilder`
```java
// NORMAL mode
public String buildFIUQuery(String cleanName, String oldNic, String newNic, String passportNo)
public String buildFIUQuery(String cleanName, String oldNic, String newNic)  // overload, passportNo=null
public String buildUNQuery(String cleanName)
public String buildLocalWatchListQuery(String cleanName, String oldNic, String newNic)
public String buildFIUOrgQuery(String cleanName, String registrationNo)
public String buildLXNXQuery(String cleanName)

// RESTRICTED mode — single-token names
public String buildRestrictedFIUQuery(String name, String oldNic, String newNic, String passportNo)
public String buildRestrictedUNQuery(String name)
public String buildRestrictedLocalWatchQuery(String name, String oldNic, String newNic)
public String buildRestrictedLXNXQuery(String name)  // exact phrase only, no fuzzy/wildcard

// NIC_ONLY mode — all-initials names
public String buildNicOnlyFIUQuery(String oldNic, String newNic, String passportNo)
public String buildNicOnlyLocalWatchQuery(String oldNic, String newNic)
```

## `SolrQueryExecutor`
```java
SolrQueryExecutor(CloudSolrClient, ConfigLoader)
public List<ScreeningCandidate> executeFIU(String queryStr)
public List<ScreeningCandidate> executeFIUOrg(String queryStr)
public List<ScreeningCandidate> executeUN(String queryStr)
public List<ScreeningCandidate> executeLocalWatch(String queryStr)
public List<ScreeningCandidate> executeLXNX(String queryStr)  // lxnx-specific edismax params
private SolrDocumentList query(String queryStr, String coreName)
```

## `ScreeningCandidate`
```java
protected ScreeningCandidate(String docId, String coreName)
public String getDocId()
public String getCoreName()
public abstract List<String> getNameVariants()
public abstract List<String> getIdDocuments()
protected void addIfPresent(List<String> target, SolrDocument doc, String field)
protected void addMultiIfPresent(List<String> target, SolrDocument doc, String field)
```

## `SolrClientConfig`
```java
@Bean(destroyMethod = "close")
public CloudSolrClient cloudSolrClient(ConfigLoader configLoader)
```

## `MatchRateClient`
```java
MatchRateClient(ConfigLoader)
@PostConstruct public void init()
public CompletableFuture<List<Map<String, Object>>> getMatchRates(
        String keyword, List<Map<String, Object>> dataArr, String screeningMode)
@PreDestroy public void destroy()
```

## `ScreeningMetrics`
```java
ScreeningMetrics(ConfigLoader)
@PostConstruct public void init()
public void startRun(long totalCustomerCount)
public void endRun()
public void reset()
public void recordRead(int count)
public void recordEligible(int count)
public long getTotalEligible()
public void recordScreened(int count)
public void recordCandidates(int count)
public void recordScored(int count)
public void recordAboveThreshold(int count)
public void recordScoringLatency(long ms)
public void recordAlertsPersisted(int count)
public void updateAlertBufferDepth(long depth)
```

## `NameNormalizer`
```java
public static Result classify(String rawName)
public static String expandDots(String name)
public static String splitCamelCase(String name)
public static boolean containsInvalidPersonNameChars(String name)
public static String stripInvalidPersonNameChars(String name)
```

## `NameRefinerUtil`
```java
public static String refineName(String name)
```

## `SriLankanNicUtil`
```java
public static NicPair resolve(String rawNic)
private static NicStatus detect(String cleaned)
private static boolean validateLogic(int year, int dayValue, String nic)
private static String toNewFormat(String cleanedOld)
private static String toOldFormat(String cleanedNew)
private static String auditAndClean(String input)
```

## `UuidV7`
```java
public static String generate()
```

## `ScreeningExceptionController`
```java
ScreeningExceptionController(ScreeningExceptionRepository, ScreeningRunRepository)
@GetMapping("/runs")
    ResponseEntity<List<RunSummaryDto>> getRuns()
@GetMapping("/runs/{runId}/exceptions")
    ResponseEntity<Page<SummaryExceptionDto>> getExceptionsByRun(
        @PathVariable String runId,
        @RequestParam(defaultValue="ALL") ExceptionFilter filter,
        Pageable pageable)
@GetMapping("/exceptions")
    ResponseEntity<Page<SummaryExceptionDto>> getByDateRange(
        @RequestParam LocalDate startDate, @RequestParam LocalDate endDate, Pageable pageable)
@GetMapping("/exceptions/{id}")
    ResponseEntity<DetailedExceptionDto> getById(@PathVariable String id)
```

## `DataSourceConfig`
```java
@Primary @Bean public DataSource postgresDataSource()
@Bean("oracleDataSource") public DataSource oracleDataSource()
@Bean("oracleJdbcTemplate") public JdbcTemplate oracleJdbcTemplate(
    @Qualifier("oracleDataSource") DataSource dataSource)
```

## Repositories — Key Custom Queries

### `AlertRepository`
```java
@Query List<MiniAlert> findCustomersToOmit(@Param("statuses") List<AlertStatus> statuses)
```

### `ScreeningScheduleRepository`
```java
@Query List<ActiveScreeningView> findActiveSchedules()
```

### `SanctionTypeRepository`
```java
@Query List<SanctionTypeView> findActiveSanctionTypes()
```

### `ScreeningExceptionRepository`
```java
Page<ScreeningExceptionEntity> findByRunId(String runId, Pageable pageable)
Page<ScreeningExceptionEntity> findByRunIdAndAction(String runId, String action, Pageable pageable)
Page<ScreeningExceptionEntity> findByRunIdAndScreeningMode(String runId, String screeningMode, Pageable pageable)
@Query Page<ScreeningExceptionEntity> findHitsByRunId(@Param("runId") String runId, Pageable pageable)
Page<ScreeningExceptionEntity> findByRunIdAndFailureReasonIsNotNull(String runId, Pageable pageable)
Page<ScreeningExceptionEntity> findByCreatedAtBetween(LocalDateTime start, LocalDateTime end, Pageable pageable)
@Query Object[] getCountsByRunId(@Param("runId") String runId)  // [total, skipped, restricted, transformed]
@Query long countHitsByRunId(@Param("runId") String runId)
@Query long countNicOnlyByRunId(@Param("runId") String runId)
@Query long countInfraFailedByRunId(@Param("runId") String runId)
```

## `ScreeningTrigger`
```java
ScreeningTrigger(AlertRepository repo)
@Override public void run(ApplicationArguments args)
// ScreeningProcessor injected via @Lazy @Autowired to avoid circular dependency
```
