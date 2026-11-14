package com.sh.engine.task;

import cn.hutool.extra.spring.SpringUtil;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.StaticApplicationContext;
import com.alibaba.fastjson.JSONObject;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.MapPropertySource;
import com.sh.engine.model.asr.AsrSegment;
import com.sh.engine.model.highlight.core.OcrTextDetection;
import com.sh.engine.service.*;
import dev.langchain4j.data.message.Content;
import org.junit.*;
import org.redisson.api.*;
import org.redisson.api.annotation.RInject;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.io.*;
import java.net.ServerSocket;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** 通过显式路径启动隔离 Redis；不读取应用的任何 Redis 地址或凭据。 */
public class ComputeTasksIntegrationTest {
    private static Process redisProcess;
    private static int port;
    private static Path redisDirectory;
    private RedissonClient driverRedis;
    private RedissonClient workerRedis;
    private TaskClient client;
    private ComputeTaskWorkers workers;
    private AnnotationConfigApplicationContext context;
    private RedisProperties redis;
    private String env;

    @BeforeClass
    public static void startRedis() throws Exception {
        String executable = System.getProperty("task.test.redis-server");
        Assume.assumeTrue("Set -Dtask.test.redis-server to run isolated Redis integration tests",
                executable != null && Files.isExecutable(Paths.get(executable)));
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        redisDirectory = Files.createTempDirectory("streammove-redis-test-");
        redisProcess = new ProcessBuilder(executable, "--bind", "127.0.0.1", "--port", String.valueOf(port),
                "--save", "", "--appendonly", "no", "--dir", redisDirectory.toString())
                .redirectErrorStream(true).redirectOutput(redisDirectory.resolve("redis.log").toFile()).start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            try (java.net.Socket ignored = new java.net.Socket("127.0.0.1", port)) { return; }
            catch (IOException waiting) { Thread.sleep(20); }
        }
        throw new IllegalStateException("Isolated Redis did not start");
    }

    @AfterClass
    public static void stopRedis() throws Exception {
        if (redisProcess != null) {
            redisProcess.destroy();
            if (!redisProcess.waitFor(5, TimeUnit.SECONDS)) { redisProcess.destroyForcibly().waitFor(); }
        }
        if (redisDirectory != null) {
            Files.deleteIfExists(redisDirectory.resolve("redis.log"));
            Files.deleteIfExists(redisDirectory);
        }
    }

    @Before
    public void startNodes() {
        redis = new RedisProperties(); redis.setHost("127.0.0.1"); redis.setPort(port);
        env = "test-" + UUID.randomUUID();
        ComputeTaskConfiguration config = new ComputeTaskConfiguration();
        driverRedis = config.computeRedissonClient(redis);
        workerRedis = config.computeRedissonClient(redis);
        client = client(driverRedis, false, env);
        context = new AnnotationConfigApplicationContext();
        context.getBeanFactory().registerSingleton("ocrService", mock(OcrService.class));
        context.getBeanFactory().registerSingleton("asrService", mock(AsrService.class));
        context.getBeanFactory().registerSingleton("llmService", mock(LlmService.class));
        context.getBeanFactory().registerSingleton("ossUploadService", mock(OssUploadService.class));
        context.refresh();
        workers = new ComputeTaskWorkers(
                client(workerRedis, true, env), context.getBeanFactory());
        workers.afterSingletonsInstantiated();
    }

    @After
    public void stopNodes() throws Exception {
        if (workers != null) { workers.destroy(); }
        if (workerRedis != null) { workerRedis.shutdown(); }
        if (driverRedis != null) { driverRedis.shutdown(); }
        if (context != null) { context.close(); }
    }

    @Test
    public void ossInputsAndTypedResultsRoundTripThroughExistingServices() throws Exception {
        OssUploadService oss = context.getBean(OssUploadService.class);
        doAnswer(call -> { ((OutputStream) call.getArgument(1)).write(new byte[]{7, 8}); return null; })
                .when(oss).download(anyString(), any(OutputStream.class));
        when(oss.getImageDataUrl(anyString())).thenReturn("data:image/jpeg;base64,AQID");
        when(context.getBean(OcrService.class).recognize(any(byte[].class), anyString()))
                .thenReturn(Collections.singletonList(new OcrTextDetection("text", .9f, Arrays.asList(1, 2))));
        when(context.getBean(AsrService.class).transcribeAudio(any(File.class), eq(40))).thenAnswer(call -> {
            assertArrayEquals(new byte[]{7, 8}, Files.readAllBytes(((File) call.getArgument(0)).toPath()));
            return Collections.singletonList(AsrSegment.builder().startTime(40).endTime(42).text("speech")
                    .emotion("happy").event("Speech").build());
        });
        when(context.getBean(LlmService.class).chat(org.mockito.ArgumentMatchers.<List<Content>>any())).thenAnswer(call -> {
            assertEquals(3, ((List<?>) call.getArgument(0)).size());
            JSONObject result = new JSONObject(); result.put("finalResponse", "model result"); return result;
        });
        ComputeTasks tasks = new ComputeTasks(client);
        RExecutorFuture<List<List<OcrTextDetection>>> ocr = tasks.ocr(Arrays.asList("images/one.jpg", "images/two.jpg"));
        List<List<OcrTextDetection>> recognized = client.result(ocr, Duration.ofSeconds(5));
        assertEquals(2, recognized.size());
        assertEquals("text", recognized.get(0).get(0).getText());
        assertEquals("text", client.resultAsync(ocr).get(5, TimeUnit.SECONDS).get(1).get(0).getText());
        AsrSegment speech = client.result(tasks.asr("audio/one.wav", 40), Duration.ofSeconds(5)).get(0);
        assertEquals(40, speech.getStartTime());
        assertEquals("happy", speech.getEmotion());
        assertEquals("Speech", speech.getEvent());
        assertEquals("model result", client.result(tasks.llm("review", Arrays.asList("one.jpg", "two.jpg")),
                Duration.ofSeconds(5)).getString("finalResponse"));
        verify(context.getBean(LlmService.class), never()).chatAsync(anyString(), any());
    }

    @Test
    public void inlineAndZipImageBatchesRoundTripThroughRedisInOrder() throws Exception {
        OssUploadService oss = context.getBean(OssUploadService.class);
        Map<String, byte[]> objects = new ConcurrentHashMap<>();
        when(oss.uploadAndGetUrl(any(File.class), anyString())).thenAnswer(call -> {
            objects.put(call.getArgument(1), Files.readAllBytes(((File) call.getArgument(0)).toPath())); return "unused-url";
        });
        doAnswer(call -> { ((OutputStream) call.getArgument(1)).write(objects.get(call.getArgument(0))); return null; })
                .when(oss).download(anyString(), any(OutputStream.class));
        when(context.getBean(OcrService.class).recognize(any(byte[].class), anyString())).thenAnswer(call ->
                Collections.singletonList(new OcrTextDetection(String.valueOf(((byte[]) call.getArgument(0))[0]), 1f, Collections.emptyList())));
        ComputeTasks tasks = new ComputeTasks(client);
        java.lang.reflect.Field field = ComputeTasks.class.getDeclaredField("oss"); field.setAccessible(true); field.set(tasks, oss);
        List<byte[]> images = new ArrayList<>(); List<String> names = new ArrayList<>();
        for (int index = 0; index < 9; index++) { byte[] image = new byte[4096]; image[0] = (byte) index; images.add(image); names.add("same.jpg"); }
        List<List<OcrTextDetection>> inline = tasks.recognizeBatch(images, names);
        for (int index = 0; index < inline.size(); index++) { assertEquals(String.valueOf(index), inline.get(index).get(0).getText()); }
        verify(oss, never()).uploadAndGetUrl(any(File.class), anyString());
        byte[] large = new byte[32769]; large[0] = 40;
        List<List<OcrTextDetection>> packed = tasks.recognizeBatch(Arrays.asList(large, new byte[]{41}), Arrays.asList("same.jpg", "same.jpg"));
        assertEquals("40", packed.get(0).get(0).getText()); assertEquals("41", packed.get(1).get(0).getText());
        verify(oss).uploadAndGetUrl(any(File.class), anyString()); verify(oss).download(anyString(), any(OutputStream.class));
        verify(oss, timeout(2000)).delete(anyString());
        assertTrue(client.executor(TaskType.OCR).getTaskIds().isEmpty());
    }

    @Test
    public void everyTypeIsAcquiredByItsOwnPoolAndFinishedTasksAreRemoved() throws Exception {
        String started = env + ":all-types-started", release = latch();
        driverRedis.getCountDownLatch(started).trySetCount(TaskType.values().length);
        Map<TaskType, RExecutorFuture<String>> submitted = new EnumMap<>(TaskType.class);
        try {
            for (TaskType type : TaskType.values()) {
                submitted.put(type, client.submit(type, new BlockingTask(started, release, null)));
            }
            assertTrue(driverRedis.getCountDownLatch(started).await(5, TimeUnit.SECONDS));
            for (Map.Entry<TaskType, RExecutorFuture<String>> entry : submitted.entrySet()) {
                assertTrue(client.executor(entry.getKey()).hasTask(entry.getValue().getTaskId()));
                assertEquals(1, client.executor(entry.getKey()).getTaskCount());
            }
        } finally {
            driverRedis.getCountDownLatch(release).countDown();
        }
        for (Map.Entry<TaskType, RExecutorFuture<String>> entry : submitted.entrySet()) {
            TaskType type = entry.getKey();
            String thread = client.result(entry.getValue(), Duration.ofSeconds(5));
            assertTrue(thread.startsWith("compute-" + type.name().toLowerCase(Locale.ROOT) + "-"));
            assertFalse(client.executor(type).hasTask(entry.getValue().getTaskId()));
            assertTrue(client.executor(type).getTaskIds().isEmpty());
            assertEquals(0, client.executor(type).getTaskCount());
        }
    }

    @Test
    public void busyOcrDoesNotOccupyOtherTypePools() throws Exception {
        String started = env + ":ocr-started", release = latch();
        driverRedis.getCountDownLatch(started).trySetCount(TaskType.OCR.getThreads());
        List<RExecutorFuture<String>> running = new ArrayList<>();
        for (int i = 0; i < TaskType.OCR.getThreads(); i++) {
            running.add(client.submit(TaskType.OCR, new BlockingTask(started, release, null)));
        }
        assertTrue(driverRedis.getCountDownLatch(started).await(5, TimeUnit.SECONDS));
        RExecutorFuture<String> second = client.submit(TaskType.OCR, new BlockingTask(null, null, null));
        assertTrue(client.result(client.submit(TaskType.ASR, new BlockingTask(null, null, null)), Duration.ofSeconds(3)).startsWith("compute-asr-"));
        assertFalse(second.isDone());
        driverRedis.getCountDownLatch(release).countDown();
        for (RExecutorFuture<String> task : running) {
            assertTrue(client.result(task, Duration.ofSeconds(3)).startsWith("compute-ocr-"));
        }
        assertTrue(client.result(second, Duration.ofSeconds(3)).startsWith("compute-ocr-"));
    }

    @Test
    public void waitTimeoutDoesNotCancelExecution() throws Exception {
        String started = latch(), release = latch();
        RExecutorFuture<String> task = client.submit(TaskType.ASR, new BlockingTask(started, release, null));
        assertTrue(driverRedis.getCountDownLatch(started).await(5, TimeUnit.SECONDS));
        try { client.result(task, Duration.ofMillis(20)); fail("timeout expected"); }
        catch (TimeoutException expected) { }
        assertFalse(task.isDone());
        CompletableFuture<String> next = client.resultAsync(task, Duration.ofSeconds(5));
        driverRedis.getCountDownLatch(release).countDown();
        assertTrue(next.get(5, TimeUnit.SECONDS).startsWith("compute-asr-"));
    }

    @Test
    public void executionTimeoutInterruptsCooperativeTask() throws Exception {
        String started = latch(), release = latch(), finished = latch();
        // 单独验证 SDK 的短超时，不为测试给生产参数增加可变入口。
        String name = env + ":short-timeout";
        RScheduledExecutorService executor = workerRedis.getExecutorService(name);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            executor.registerWorkers(WorkerOptions.defaults().workers(1).executorService(pool).taskTimeout(1, TimeUnit.SECONDS));
            RExecutorFuture<String> task = driverRedis.getExecutorService(name)
                    .submitAsync(new BlockingTask(started, release, finished));
            assertTrue(driverRedis.getCountDownLatch(started).await(5, TimeUnit.SECONDS));
            try { client.result(task, Duration.ofSeconds(5)); fail("execution timeout expected"); }
            catch (CancellationException | ExecutionException expected) { }
            assertTrue(driverRedis.getCountDownLatch(finished).await(5, TimeUnit.SECONDS));
        } finally { executor.deregisterWorkers(); pool.shutdownNow(); }
    }

    @Test
    public void serviceFailureIsNotReportedAsEmptySuccess() throws Exception {
        OssUploadService oss = context.getBean(OssUploadService.class);
        doThrow(new IOException("download rejected")).when(oss).download(anyString(), any(OutputStream.class));
        RExecutorFuture<List<List<OcrTextDetection>>> task = new ComputeTasks(client).ocr(Collections.singletonList("failed.jpg"));
        try { client.result(task, Duration.ofSeconds(5)); fail("failure expected"); }
        catch (ExecutionException expected) { assertNotNull(expected.getCause()); }
        assertFalse(client.executor(TaskType.OCR).hasTask(task.getTaskId()));
        assertEquals(0, client.executor(TaskType.OCR).getTaskCount());
    }

    @Test
    public void driverDoesNotRegisterWorkersAndTaskDoesNotSubmit() throws Exception {
        ComputeTaskWorkers driver = new ComputeTaskWorkers(client, context.getBeanFactory());
        driver.afterSingletonsInstantiated(); driver.destroy();
        assertFalse(client.executor(TaskType.OCR).isShutdown());
        TaskClient task = client(workerRedis, true, env);
        try { task.submit(TaskType.ASR, new BlockingTask(null, null, null)); fail("recursive submission allowed"); }
        catch (IllegalStateException expected) { }
    }

    @Test
    public void stoppingOneWorkerNodeLeavesOtherNodeUsable() throws Exception {
        RedissonClient another = new ComputeTaskConfiguration().computeRedissonClient(redis);
        ComputeTaskWorkers other = new ComputeTaskWorkers(
                client(another, true, env), context.getBeanFactory());
        try {
            other.afterSingletonsInstantiated(); workers.destroy();
            assertFalse(client.executor(TaskType.ASR).isShutdown());
            assertTrue(client.result(client.submit(TaskType.ASR, new BlockingTask(null, null, null)), Duration.ofSeconds(5)).startsWith("compute-asr-"));
        } finally { other.destroy(); another.shutdown(); }
    }

    @Test
    public void expiredTaskIsSkippedWhenWorkerArrivesLater() throws Exception {
        String lateEnv = env + "-late", started = latch();
        TaskClient lateDriver = client(driverRedis, false, lateEnv);
        RExecutorFuture<String> task = lateDriver.executor(TaskType.ASR)
                .submitAsync(new BlockingTask(started, null, null), 1, TimeUnit.SECONDS);
        Thread.sleep(1200);
        ComputeTaskWorkers lateWorker = new ComputeTaskWorkers(
                client(workerRedis, true, lateEnv), context.getBeanFactory());
        try {
            lateWorker.afterSingletonsInstantiated();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (lateDriver.executor(TaskType.ASR).hasTask(task.getTaskId()) && System.nanoTime() < deadline) { Thread.sleep(10); }
            assertFalse(lateDriver.executor(TaskType.ASR).hasTask(task.getTaskId()));
            assertEquals(1, driverRedis.getCountDownLatch(started).getCount());
        } finally { lateWorker.destroy(); }
    }

    @Test
    public void existingLettuceClientStillConnectsWithAlignedNetty() {
        LettuceConnectionFactory lettuce = new LettuceConnectionFactory("127.0.0.1", port);
        lettuce.afterPropertiesSet();
        org.springframework.data.redis.connection.RedisConnection connection = lettuce.getConnection();
        try {
            assertEquals("PONG", connection.ping());
        } finally { connection.close(); lettuce.destroy(); }
    }

    private TaskClient client(RedissonClient redis, boolean taskMode, String env) {
        StandardEnvironment environment = new StandardEnvironment();
        Map<String, Object> values = new HashMap<>();
        values.put("streammove.mode", taskMode ? "task" : "driver");
        environment.getPropertySources().addFirst(new MapPropertySource("compute-test", values));
        ApplicationContext previous = SpringUtil.getApplicationContext();
        try (StaticApplicationContext roleContext = new StaticApplicationContext()) {
            roleContext.setEnvironment(environment);
            new SpringUtil().setApplicationContext(roleContext);
            return new TaskClient(redis, env);
        } finally {
            new SpringUtil().setApplicationContext(previous);
        }
    }

    private String latch() {
        String key = env + ":" + UUID.randomUUID();
        driverRedis.getCountDownLatch(key).trySetCount(1); return key;
    }

    public static class BlockingTask implements Callable<String>, Serializable {
        private static final long serialVersionUID = 1L;
        private final String started, release, finished;
        @RInject private transient RedissonClient redis;
        public BlockingTask(String started, String release, String finished) {
            this.started = started; this.release = release; this.finished = finished;
        }
        @Override public String call() throws Exception {
            if (started != null) { redis.getCountDownLatch(started).countDown(); }
            try {
                if (release != null) { redis.getCountDownLatch(release).await(); }
                return Thread.currentThread().getName();
            } finally {
                if (finished != null) { redis.getCountDownLatch(finished).countDown(); }
            }
        }
    }
}
