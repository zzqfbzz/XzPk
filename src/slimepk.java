import java.util.Random;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class slimepk {

    static class ChunkInfo {
        int chunkX;
        int chunkZ;
        int worldX;
        int worldZ;
        int slimeCount;
        int areaSize;

        ChunkInfo(int chunkX, int chunkZ, int slimeCount, int areaSize) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.worldX = chunkX * 16 + 8;
            this.worldZ = chunkZ * 16 + 8;
            this.slimeCount = slimeCount;
            this.areaSize = areaSize;
        }

        @Override
        public String toString() {
            // 修复百分比计算：应该是 (史莱姆区块数 / 总区块数) * 100
            // 总区块数 = areaSize * areaSize
            int totalBlocksInArea = areaSize * areaSize;
            double percentage = (slimeCount * 100.0) / totalBlocksInArea;
            return String.format("-n=%d|x,z=%d,%d|X,Z=%d,%d|%d*%d|%.1f%%",
                    slimeCount, worldX, worldZ, chunkX, chunkZ,
                    areaSize, areaSize, percentage);
        }
    }

    static PriorityBlockingQueue<ChunkInfo> topChunks = new PriorityBlockingQueue<>(50,
            (a, b) -> Integer.compare(a.slimeCount, b.slimeCount));

    static PriorityBlockingQueue<ChunkInfo> bottomChunks = new PriorityBlockingQueue<>(50,
            (a, b) -> Integer.compare(b.slimeCount, a.slimeCount));

    static AtomicInteger processedCount = new AtomicInteger(0);
    static int totalAreas = 0;
    static long startTime = 0;

    public static boolean isSlimeChunk(int chunkX, int chunkZ, long seed) {
        Random rnd = new Random(seed +
                (long) chunkX * chunkX * 0x4c1906L +
                (long) chunkX * 0x5ac0dbL +
                (long) chunkZ * chunkZ * 0x4307a7L +
                (long) chunkZ * 0x5f24fL ^ 0x3ad8025fL);
        return rnd.nextInt(10) == 0;
    }

    public static int countSlimeChunksInArea(int startChunkX, int startChunkZ, long seed, int areaSize) {
        int count = 0;
        // 确保正确计算区域内的所有区块
        for (int x = startChunkX; x < startChunkX + areaSize; x++) {
            for (int z = startChunkZ; z < startChunkZ + areaSize; z++) {
                if (isSlimeChunk(x, z, seed)) {
                    count++;
                }
            }
        }
        return count;
    }

    public static synchronized void updateChunkLists(int chunkX, int chunkZ, int slimeCount, int areaSize) {
        ChunkInfo newChunk = new ChunkInfo(chunkX, chunkZ, slimeCount, areaSize);

        // 更新最多的50个
        if (topChunks.size() < 50) {
            topChunks.offer(newChunk);
        } else if (slimeCount > topChunks.peek().slimeCount) {
            topChunks.poll();
            topChunks.offer(newChunk);
        }

        // 更新最少的50个
        if (bottomChunks.size() < 50) {
            bottomChunks.offer(newChunk);
        } else if (slimeCount < bottomChunks.peek().slimeCount) {
            bottomChunks.poll();
            bottomChunks.offer(newChunk);
        }
    }

    public static void showProgress() {
        int current = processedCount.get();
        int width = 50;
        int progress = (int) ((double) current / totalAreas * width);
        long elapsedTime = System.currentTimeMillis() - startTime;

        StringBuilder sb = new StringBuilder();
        sb.append("\r[");
        for (int i = 0; i < width; i++) {
            if (i < progress) sb.append("=");
            else if (i == progress) sb.append(">");
            else sb.append(" ");
        }
        sb.append("] ");
        sb.append(String.format("%.1f%%", (double) current / totalAreas * 100));
        sb.append(" (").append(current).append("/").append(totalAreas).append(")");
        sb.append(" 耗时: ").append(formatTime(elapsedTime));

        System.out.print(sb.toString());
    }

    public static String formatTime(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        seconds = seconds % 60;
        return minutes > 0 ? minutes + "分" + seconds + "秒" : seconds + "秒";
    }

    static class CalculationTask implements Runnable {
        private final int startX;
        private final int startZ;
        private final long seed;
        private final int areaSize;

        public CalculationTask(int startX, int startZ, long seed, int areaSize) {
            this.startX = startX;
            this.startZ = startZ;
            this.seed = seed;
            this.areaSize = areaSize;
        }

        @Override
        public void run() {
            int slimeCount = countSlimeChunksInArea(startX, startZ, seed, areaSize);
            updateChunkLists(startX, startZ, slimeCount, areaSize);

            int current = processedCount.incrementAndGet();
            if (current % 100 == 0 || current == totalAreas) {
                showProgress();
            }
        }
    }

    public static void calculateMultipleAreasParallel(int xMin, int xMax, int zMin, int zMax,
                                                      long seed, int areaSize) {
        // 确保区域边界计算正确
        int startX = (xMin / areaSize) * areaSize;
        int startZ = (zMin / areaSize) * areaSize;
        int endX = ((xMax + areaSize - 1) / areaSize) * areaSize;
        int endZ = ((zMax + areaSize - 1) / areaSize) * areaSize;

        // 计算总区域数
        int areasX = (endX - startX) / areaSize;
        int areasZ = (endZ - startZ) / areaSize;
        totalAreas = areasX * areasZ;

        System.out.println("计算范围: 区块X[" + startX + " to " + (endX - 1) + "], Z[" +
                startZ + " to " + (endZ - 1) + "]");
        System.out.println("每个区域大小: " + areaSize + "x" + areaSize + " 区块");
        System.out.println("总区域数: " + totalAreas);
        System.out.println("开始计算...");

        topChunks.clear();
        bottomChunks.clear();
        processedCount.set(0);
        startTime = System.currentTimeMillis();

        int processors = Runtime.getRuntime().availableProcessors();
        ExecutorService executor = Executors.newFixedThreadPool(processors);

        try {
            // 提交所有计算任务
            for (int x = startX; x < endX; x += areaSize) {
                for (int z = startZ; z < endZ; z += areaSize) {
                    executor.submit(new CalculationTask(x, z, seed, areaSize));
                }
            }

            executor.shutdown();
            if (!executor.awaitTermination(1, TimeUnit.HOURS)) {
                System.out.println("\n计算超时");
            }

        } catch (InterruptedException e) {
            System.err.println("计算被中断");
            Thread.currentThread().interrupt();
        }

        long totalTime = System.currentTimeMillis() - startTime;
        System.out.println("\n\n计算完成！耗时: " + formatTime(totalTime));

        // 获取并排序结果
        List<ChunkInfo> topResults = new ArrayList<>(topChunks);
        List<ChunkInfo> bottomResults = new ArrayList<>(bottomChunks);
        Collections.sort(topResults, (a, b) -> Integer.compare(b.slimeCount, a.slimeCount));
        Collections.sort(bottomResults, (a, b) -> Integer.compare(a.slimeCount, b.slimeCount));

        // 输出最多的50个
        System.out.println("\n=== 史莱姆区块最多的50个区域 ===");
        for (int i = 0; i < Math.min(50, topResults.size()); i++) {
            System.out.println(topResults.get(i));
        }

        // 输出最少的50个
        System.out.println("\n=== 史莱姆区块最少的50个区域 ===");
        for (int i = 0; i < Math.min(50, bottomResults.size()); i++) {
            System.out.println(bottomResults.get(i));
        }

        // 统计信息
        System.out.println("\n=== 统计信息 ===");
        System.out.println("扫描区域总数: " + totalAreas);
        System.out.println("区域大小: " + areaSize + "x" + areaSize);

        if (!topResults.isEmpty()) {
            int maxSlime = topResults.get(0).slimeCount;
            int totalBlocks = areaSize * areaSize;
            System.out.println("最多史莱姆区块: " + maxSlime + "/" + totalBlocks +
                    " (" + String.format("%.1f", maxSlime * 100.0 / totalBlocks) + "%)");
        }
        if (!bottomResults.isEmpty()) {
            int minSlime = bottomResults.get(0).slimeCount;
            int totalBlocks = areaSize * areaSize;
            System.out.println("最少史莱姆区块: " + minSlime + "/" + totalBlocks +
                    " (" + String.format("%.1f", minSlime * 100.0 / totalBlocks) + "%)");
        }

        // 验证计算：理论概率应该是10%
        double expectedPercentage = 10.0;
        System.out.println("理论史莱姆区块概率: " + expectedPercentage + "%");
    }

    public static void main(String[] args) {
        long seed = 2950649267509295309L;
        int areaSize = 8;

        int xMin = -600;
        int xMax = 600;
        int zMin = -600;
        int zMax = 600;

        if (args.length >= 4) {
            try {
                xMin = Integer.parseInt(args[0]);
                xMax = Integer.parseInt(args[1]);
                zMin = Integer.parseInt(args[2]);
                zMax = Integer.parseInt(args[3]);

                if (args.length >= 5) {
                    areaSize = Integer.parseInt(args[4]);
                }
                if (args.length >= 6) {
                    seed = Long.parseLong(args[5]);
                }
            } catch (NumberFormatException e) {
                System.out.println("参数错误，使用默认值");
            }
        }

        System.out.println("种子: " + seed);
        System.out.println("区域大小: " + areaSize + "x" + areaSize);
        calculateMultipleAreasParallel(xMin, xMax, zMin, zMax, seed, areaSize);
    }
}