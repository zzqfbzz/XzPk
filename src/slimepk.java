import java.util.Random;
import java.util.ArrayList;
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
            double percentage = (slimeCount * 100.0) / (areaSize * areaSize);
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
        // 修复整数乘法隐式转换问题
        Random rnd = new Random(seed +
                (long) chunkX * chunkX * 0x4c1906L +
                (long) chunkX * 0x5ac0dbL +
                (long) chunkZ * chunkZ * 0x4307a7L +
                (long) chunkZ * 0x5f24fL ^ 0x3ad8025fL);
        return rnd.nextInt(10) == 0;
    }

    public static int countSlimeChunksInArea(int startChunkX, int startChunkZ, long seed, int areaSize) {
        int count = 0;
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

        if (topChunks.size() < 50) {
            topChunks.offer(newChunk);
        } else if (slimeCount > topChunks.peek().slimeCount) {
            topChunks.poll();
            topChunks.offer(newChunk);
        }

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
        int startX = (xMin / areaSize) * areaSize;
        int startZ = (zMin / areaSize) * areaSize;
        int endX = ((xMax + areaSize - 1) / areaSize) * areaSize;
        int endZ = ((zMax + areaSize - 1) / areaSize) * areaSize;

        int areasX = (endX - startX) / areaSize;
        int areasZ = (endZ - startZ) / areaSize;
        totalAreas = areasX * areasZ;

        System.out.println("开始计算区域: [" + startX + " to " + (endX - 1) + "] x [" +
                startZ + " to " + (endZ - 1) + "]");
        System.out.println("区域大小: " + areaSize + "x" + areaSize + " 区块");
        System.out.println("总共需要计算: " + totalAreas + " 个区域");
        System.out.println("使用多线程并行计算...");

        topChunks.clear();
        bottomChunks.clear();
        processedCount.set(0);
        startTime = System.currentTimeMillis();

        int processors = Runtime.getRuntime().availableProcessors();
        ExecutorService executor = Executors.newFixedThreadPool(processors * 2);

        try {
            for (int x = startX; x < endX; x += areaSize) {
                for (int z = startZ; z < endZ; z += areaSize) {
                    executor.submit(new CalculationTask(x, z, seed, areaSize));
                }
            }

            executor.shutdown();
            if (!executor.awaitTermination(1, TimeUnit.HOURS)) {
                System.out.println("计算超时，强制关闭线程池");
                executor.shutdownNow();
            }

        } catch (InterruptedException e) {
            System.err.println("计算被中断: " + e.getMessage());
            Thread.currentThread().interrupt();
        } finally {
            if (!executor.isShutdown()) {
                executor.shutdownNow();
            }
        }

        System.out.println("\n\n计算完成！总共耗时: " + formatTime(System.currentTimeMillis() - startTime));

        List<ChunkInfo> topResults = new ArrayList<>(topChunks);
        List<ChunkInfo> bottomResults = new ArrayList<>(bottomChunks);
        topResults.sort((a, b) -> Integer.compare(b.slimeCount, a.slimeCount));
        bottomResults.sort((a, b) -> Integer.compare(a.slimeCount, b.slimeCount));

        System.out.println("\n=== 史莱姆区块数量最多的50个区域 ===");
        for (int i = 0; i < Math.min(50, topResults.size()); i++) {
            System.out.println((i + 1) + ". " + topResults.get(i));
        }

        System.out.println("\n=== 史莱姆区块数量最少的50个区域 ===");
        for (int i = 0; i < Math.min(50, bottomResults.size()); i++) {
            System.out.println((i + 1) + ". " + bottomResults.get(i));
        }

        System.out.println("\n=== 统计信息 ===");
        System.out.println("扫描区域总数: " + totalAreas);
        System.out.println("区域大小: " + areaSize + "x" + areaSize + " 区块");

        if (!topResults.isEmpty() && !bottomResults.isEmpty()) {
            int maxPossible = areaSize * areaSize;
            ChunkInfo topFirst = topResults.get(0);
            ChunkInfo bottomFirst = bottomResults.get(0);

            System.out.println("最多史莱姆区块: " + topFirst.slimeCount + "/" + maxPossible +
                    " (" + String.format("%.1f", topFirst.slimeCount * 100.0 / maxPossible) + "%)");
            System.out.println("最少史莱姆区块: " + bottomFirst.slimeCount + "/" + maxPossible +
                    " (" + String.format("%.1f", bottomFirst.slimeCount * 100.0 / maxPossible) + "%)");
        }
    }

    public static void main(String[] args) {
        long seed = 2950649267509295309L;
        int areaSize = 8;

        int xMin = -100;
        int xMax = 100;
        int zMin = -100;
        int zMax = 100;

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
                System.out.println("参数格式错误，使用默认值");
            }
        }

        System.out.println("使用区域大小: " + areaSize + "x" + areaSize);
        calculateMultipleAreasParallel(xMin, xMax, zMin, zMax, seed, areaSize);
    }
}