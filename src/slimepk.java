import java.util.Random;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class slimepk {

    static class ChunkInfo {
        int chunkX;     // 区块坐标X
        int chunkZ;     // 区块坐标Z
        int worldX;     // 世界坐标X（区块中心）
        int worldZ;     // 世界坐标Z（区块中心）
        int slimeCount; // 史莱姆数量
        int areaSize;   // 区域大小

        ChunkInfo(int chunkX, int chunkZ, int slimeCount, int areaSize) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.worldX = chunkX * 16 + 8;  // 区块中心坐标
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

    // 最多的50个区块（使用最小堆，堆顶是最小的）
    static PriorityBlockingQueue<ChunkInfo> topChunks = new PriorityBlockingQueue<>(50,
            (a, b) -> Integer.compare(a.slimeCount, b.slimeCount));

    // 最少的50个区块（使用最大堆，堆顶是最大的）
    static PriorityBlockingQueue<ChunkInfo> bottomChunks = new PriorityBlockingQueue<>(50,
            (a, b) -> Integer.compare(b.slimeCount, a.slimeCount));

    static AtomicInteger processedCount = new AtomicInteger(0);
    static int totalAreas = 0;
    static long startTime = 0;

    public static int[] chunkToWorld(int chunkX, int chunkZ) {
        return new int[]{chunkX * 16 + 8, chunkZ * 16 + 8};
    }

    public static int[] worldToChunk(int worldX, int worldZ) {
        return new int[]{worldX >> 4, worldZ >> 4};
    }

    public static boolean isSlimeChunk(int chunkX, int chunkZ, long seed) {
        Random rnd = new Random(seed +
                (long) (chunkX * chunkX * 0x4c1906) +
                (long) (chunkX * 0x5ac0db) +
                (long) (chunkZ * chunkZ) * 0x4307a7L +
                (long) (chunkZ * 0x5f24f) ^ 0x3ad8025fL);
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

    /**
     * 线程安全的更新最多和最少的区块列表
     */
    public static synchronized void updateChunkLists(int chunkX, int chunkZ, int slimeCount, int areaSize) {
        ChunkInfo newChunk = new ChunkInfo(chunkX, chunkZ, slimeCount, areaSize);

        // 更新最多的50个区块
        if (topChunks.size() < 50) {
            topChunks.offer(newChunk);
        } else if (slimeCount > topChunks.peek().slimeCount) {
            topChunks.poll();
            topChunks.offer(newChunk);
        }

        // 更新最少的50个区块
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
        return minutes > 0 ? String.format("%d分%d秒", minutes, seconds) : String.format("%d秒", seconds);
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
        // 计算区域范围
        int startX = (xMin / areaSize) * areaSize;
        int startZ = (zMin / areaSize) * areaSize;
        int endX = ((xMax + areaSize - 1) / areaSize) * areaSize;
        int endZ = ((zMax + areaSize - 1) / areaSize) * areaSize;

        // 计算总区域数
        int areasX = (endX - startX) / areaSize;
        int areasZ = (endZ - startZ) / areaSize;
        totalAreas = areasX * areasZ;

        System.out.println("开始计算区域: [" + startX + " to " + (endX - 1) + "] x [" +
                startZ + " to " + (endZ - 1) + "]");
        System.out.println("区域大小: " + areaSize + "x" + areaSize + " 区块");
        System.out.println("总共需要计算: " + totalAreas + " 个区域");
        System.out.println("使用多线程并行计算...");

        // 初始化
        topChunks.clear();
        bottomChunks.clear();
        processedCount.set(0);
        startTime = System.currentTimeMillis();

        // 创建线程池
        int processors = Runtime.getRuntime().availableProcessors();
        ExecutorService executor = Executors.newFixedThreadPool(processors * 2);

        try {
            // 提交所有计算任务
            for (int x = startX; x < endX; x += areaSize) {
                for (int z = startZ; z < endZ; z += areaSize) {
                    executor.submit(new CalculationTask(x, z, seed, areaSize));
                }
            }

            // 等待所有任务完成
            executor.shutdown();
            executor.awaitTermination(1, TimeUnit.HOURS);

        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        // 计算完成
        System.out.println("\n\n计算完成！总共耗时: " + formatTime(System.currentTimeMillis() - startTime));

        // 获取排序后的结果
        List<ChunkInfo> topResults = new ArrayList<>(topChunks);
        List<ChunkInfo> bottomResults = new ArrayList<>(bottomChunks);
        topResults.sort((a, b) -> b.slimeCount - a.slimeCount); // 降序
        bottomResults.sort((a, b) -> a.slimeCount - b.slimeCount); // 升序

        // 输出最多的50个区块
        System.out.println("\n=== 史莱姆区块数量最多的50个区域 ===");
        for (int i = 0; i < Math.min(50, topResults.size()); i++) {
            System.out.println((i + 1) + ". " + topResults.get(i).toString());
        }

        // 输出最少的50个区块
        System.out.println("\n=== 史莱姆区块数量最少的50个区域 ===");
        for (int i = 0; i < Math.min(50, bottomResults.size()); i++) {
            System.out.println((i + 1) + ". " + bottomResults.get(i).toString());
        }

        // 输出统计信息
        System.out.println("\n=== 统计信息 ===");
        System.out.println("扫描区域总数: " + totalAreas);
        System.out.println("区域大小: " + areaSize + "x" + areaSize + " 区块");

        if (!topResults.isEmpty() && !bottomResults.isEmpty()) {
            int maxPossible = areaSize * areaSize;
            System.out.println("最多史莱姆区块: " + topResults.get(0).slimeCount + "/" + maxPossible +
                    " (" + String.format("%.1f", topResults.get(0).slimeCount * 100.0 / maxPossible) + "%)");
            System.out.println("最少史莱姆区块: " + bottomResults.get(0).slimeCount + "/" + maxPossible +
                    " (" + String.format("%.1f", bottomResults.get(0).slimeCount * 100.0 / maxPossible) + "%)");
            System.out.println("平均史莱姆区块: " + String.format("%.1f",
                    (topResults.get(0).slimeCount + bottomResults.get(0).slimeCount) / 2.0) + "/" + maxPossible);
        }

        // 输出分布统计
        System.out.println("\n=== 分布统计 ===");
        int[] distribution = new int[areaSize * areaSize + 1];
        for (ChunkInfo chunk : topResults) {
            distribution[chunk.slimeCount]++;
        }
        for (ChunkInfo chunk : bottomResults) {
            distribution[chunk.slimeCount]++;
        }

        for (int i = 0; i < distribution.length; i++) {
            if (distribution[i] > 0) {
                System.out.println("数量 " + i + ": " + distribution[i] + " 个区域");
            }
        }
    }

    public static void printCoordinateInfo(int chunkX, int chunkZ) {
        int[] worldCoords = chunkToWorld(chunkX, chunkZ);
        System.out.println("区块坐标: (" + chunkX + ", " + chunkZ + ")");
        System.out.println("世界坐标: (" + worldCoords[0] + ", " + worldCoords[1] + ")");
        System.out.println("区块范围: X[" + (chunkX * 16) + " to " + (chunkX * 16 + 15) + "], " +
                "Z[" + (chunkZ * 16) + " to " + (chunkZ * 16 + 15) + "]");
    }

    public static void main(String[] args) {

        //种子 注意后面的L不能删 他不在种子的范围里面，是编码需要
        long seed = 2950649267509295309L;

        // 范围 这里就是17*17
        int areaSize = 17;

        // x轴最小区块坐标
        int xMin = -650;

        // z轴最小区块坐标
        int xMax = 650;

        // x轴最大区块坐标
        int zMin = -650;

        // x轴最大区块坐标
        int zMax = 650;

        // 解析命令行参数
        if (args.length >= 1) {
            try {
                if (args[0].equals("-convert")) {
                    if (args.length >= 3) {
                        int chunkX = Integer.parseInt(args[1]);
                        int chunkZ = Integer.parseInt(args[2]);
                        printCoordinateInfo(chunkX, chunkZ);
                        return;
                    } else if (args.length >= 4) {
                        int worldX = Integer.parseInt(args[1]);
                        int worldZ = Integer.parseInt(args[2]);
                        int[] chunkCoords = worldToChunk(worldX, worldZ);
                        System.out.println("世界坐标: (" + worldX + ", " + worldZ + ")");
                        System.out.println("区块坐标: (" + chunkCoords[0] + ", " + chunkCoords[1] + ")");
                        printCoordinateInfo(chunkCoords[0], chunkCoords[1]);
                        return;
                    }
                }

                if (args.length >= 6) {
                    areaSize = Integer.parseInt(args[0]);
                    xMin = Integer.parseInt(args[1]);
                    xMax = Integer.parseInt(args[2]);
                    zMin = Integer.parseInt(args[3]);
                    zMax = Integer.parseInt(args[4]);
                    if (args.length >= 6) seed = Long.parseLong(args[5]);
                } else if (args.length >= 5) {
                    xMin = Integer.parseInt(args[0]);
                    xMax = Integer.parseInt(args[1]);
                    zMin = Integer.parseInt(args[2]);
                    zMax = Integer.parseInt(args[3]);
                    if (args.length >= 5) areaSize = Integer.parseInt(args[4]);
                    if (args.length >= 6) seed = Long.parseLong(args[5]);
                } else if (args.length >= 4) {
                    xMin = Integer.parseInt(args[0]);
                    xMax = Integer.parseInt(args[1]);
                    zMin = Integer.parseInt(args[2]);
                    zMax = Integer.parseInt(args[3]);
                }
            } catch (NumberFormatException e) {
                System.out.println("参数格式错误");
            }
        }

        System.out.println("使用区域大小: " + areaSize + "x" + areaSize);
        calculateMultipleAreasParallel(xMin, xMax, zMin, zMax, seed, areaSize);
    }
}